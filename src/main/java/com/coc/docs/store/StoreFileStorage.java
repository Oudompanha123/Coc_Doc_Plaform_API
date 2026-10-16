package com.coc.docs.store;

import com.coc.docs.web.ApiException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;

/**
 * The Store's files on the backend's local disk (ADR-108, ADR-111) — the
 * {@link StoreFiles} used in development and tests, and on a deployment that
 * has not chosen S3 (ADR-115):
 *
 * <pre>
 * {root}/{platform}/{version}/{environment}/{app id}/{file name}   a build
 * {root}/logos/{app id}.{png|jpg|webp}                             an app's logo
 * </pre>
 *
 * <p><b>Every path is built here, from validated parts, and checked to stay
 * under the root.</b> A version is held to {@link StoreFileRules#VERSION} and a
 * file name is reduced to {@link StoreFileRules#safeFileName}; neither can
 * carry a separator or "..", and {@link #resolve} refuses anything that
 * normalises outside the root anyway, so a crafted value in the database could
 * not reach another file either.
 *
 * <p><b>Writes are whole or absent.</b> Bytes stream to a temporary file in
 * the destination folder — never buffered in memory, since an installer can
 * be hundreds of megabytes — and are moved into place only once the size cap
 * and the content checks have passed.
 *
 * <p><b>One upload, one or two environments.</b> A file published as both
 * Production and Development is written once and placed in both environment
 * folders — hard-linked where the file system allows it, copied otherwise —
 * so each build owns its own path and can be removed without touching the
 * other.
 *
 * <p>Not a {@code @Component}: {@code StoreFilesConfig} builds it when the
 * Store is set to local storage.
 *
 * <p>// req: ADR-108, ADR-111, ADR-115
 */
public class StoreFileStorage implements StoreFiles {

    private static final Logger log = LoggerFactory.getLogger(StoreFileStorage.class);

    private final Path root;
    private final long maxFileBytes;

    public StoreFileStorage(String root, long maxFileBytes) {
        this.root = Path.of(root).toAbsolutePath().normalize();
        this.maxFileBytes = maxFileBytes;
    }

    public Path root() {
        return root;
    }

    @Override
    public long maxFileBytes() {
        return maxFileBytes;
    }

    @Override
    public StoredBuild storeBuild(
            StorePlatform platform,
            String version,
            List<StoreEnvironment> environments,
            UUID appId,
            String fileName,
            InputStream body,
            Predicate<StoreEnvironment> existingIsStale) {
        if (!StoreFileRules.isValidVersion(version)) {
            throw ApiException.badRequest("the version may use letters, digits, '.', '_', '+' and '-' only");
        }
        if (!StoreFileRules.safeFileName(fileName).equals(fileName)) {
            throw new IllegalArgumentException("file name not sanitised: " + fileName);
        }
        if (environments.isEmpty()) {
            throw new IllegalArgumentException("no environment");
        }
        Map<StoreEnvironment, String> paths = new LinkedHashMap<>();
        for (StoreEnvironment environment : environments) {
            String relative = StoreFileRules.buildKey(platform, version, environment, appId, fileName);
            if (Files.exists(resolve(relative)) && !existingIsStale.test(environment)) {
                throw ApiException.conflict("this app already has a " + platform.wire() + " " + environment.wire()
                        + " build of version " + version);
            }
            paths.put(environment, relative);
        }

        Path first = resolve(paths.values().iterator().next());
        Written written = write(first, body, maxFileBytes, "the file is larger than the Store allows");
        if (written.size() == 0) {
            deleteQuietly(written.temp());
            throw ApiException.badRequest("the file is empty");
        }
        List<Path> placed = new ArrayList<>();
        try {
            commit(written.temp(), first);
            placed.add(first);
            for (String relative : paths.values()) {
                Path target = resolve(relative);
                if (target.equals(first)) {
                    continue;
                }
                Files.createDirectories(target.getParent());
                Files.deleteIfExists(target);
                try {
                    Files.createLink(target, first);
                } catch (UnsupportedOperationException | IOException e) {
                    Files.copy(first, target, StandardCopyOption.REPLACE_EXISTING);
                }
                placed.add(target);
            }
        } catch (IOException | RuntimeException e) {
            placed.forEach(StoreFileStorage::deleteQuietly);
            deleteQuietly(written.temp());
            if (e instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new UncheckedIOException("Could not place the uploaded file", (IOException) e);
        }
        return new StoredBuild(paths, written.size(), written.sha256());
    }

    /**
     * Streams a logo to {@code logos/{appId}.{ext}}, checking that the bytes
     * really are the image type claimed — the logo route is public and sends
     * that type back, so a mislabelled upload must not get through.
     */
    @Override
    public StoredFile storeLogo(UUID appId, String contentType, InputStream body) {
        String extension = StoreFileRules.LOGO_TYPES.get(contentType);
        if (extension == null) {
            throw ApiException.badRequest("a logo must be a PNG, JPEG or WebP image");
        }
        String relative = StoreFileRules.logoKey(appId, extension);
        Path target = resolve(relative);
        Written written = write(target, body, StoreFileRules.MAX_LOGO_BYTES, "a logo must be 2 MB or smaller");
        if (!matchesMagic(written.temp(), contentType)) {
            deleteQuietly(written.temp());
            throw ApiException.badRequest("the logo's content is not the image type it claims to be");
        }
        commit(written.temp(), target);
        // A logo replaced by one of another type leaves its old file behind otherwise.
        for (String other : StoreFileRules.LOGO_TYPES.values()) {
            if (!other.equals(extension)) {
                deleteQuietly(resolve(StoreFileRules.logoKey(appId, other)));
            }
        }
        return new StoredFile(relative, written.size());
    }

    @Override
    public Delivery deliver(String relativePath, String fileName, String contentType) {
        Path path = resolve(relativePath);
        if (!Files.isRegularFile(path)) {
            throw ApiException.notFound("build file");
        }
        return new Delivery.LocalFile(path);
    }

    @Override
    public Resource openLogo(String relativePath) {
        Path path = resolve(relativePath);
        if (!Files.isRegularFile(path)) {
            throw ApiException.notFound("logo");
        }
        return new FileSystemResource(path);
    }

    /** The absolute path of a stored file, refusing anything that would leave the root. */
    public Path resolve(String relativePath) {
        Path resolved = root.resolve(relativePath).normalize();
        if (!resolved.startsWith(root) || resolved.equals(root)) {
            throw new IllegalArgumentException("path escapes the store root: " + relativePath);
        }
        return resolved;
    }

    /**
     * Removes a stored file and then any folders it leaves empty, up to (not
     * including) the root — so removing the last build of a version does not
     * leave an empty {@code android/1.2.0/production/} tree behind. A missing
     * file is not an error.
     */
    @Override
    public void delete(String relativePath) {
        Path file = resolve(relativePath);
        deleteQuietly(file);
        Path dir = file.getParent();
        while (dir != null && dir.startsWith(root) && !dir.equals(root)) {
            try {
                Files.deleteIfExists(dir);
            } catch (DirectoryNotEmptyException e) {
                return;
            } catch (IOException e) {
                log.warn("Could not remove empty store folder {}", dir, e);
                return;
            }
            dir = dir.getParent();
        }
    }

    private record Written(Path temp, long size, String sha256) {}

    private Written write(Path target, InputStream body, long limit, String tooLarge) {
        Path temp;
        try {
            Files.createDirectories(target.getParent());
            temp = Files.createTempFile(target.getParent(), ".upload-", ".part");
        } catch (IOException e) {
            throw new UncheckedIOException("Could not prepare the store folder " + target.getParent(), e);
        }
        MessageDigest sha256 = sha256();
        long size = 0;
        try (OutputStream out = new DigestOutputStream(Files.newOutputStream(temp), sha256)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = body.read(buffer)) != -1) {
                size += read;
                if (size > limit) {
                    throw ApiException.withCode(HttpStatus.PAYLOAD_TOO_LARGE, "too_large", tooLarge);
                }
                out.write(buffer, 0, read);
            }
        } catch (IOException e) {
            deleteQuietly(temp);
            throw new UncheckedIOException("Could not write the uploaded file", e);
        } catch (RuntimeException e) {
            deleteQuietly(temp);
            throw e;
        }
        return new Written(temp, size, HexFormat.of().formatHex(sha256.digest()));
    }

    private static void commit(Path temp, Path target) {
        try {
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            deleteQuietly(temp);
            throw new UncheckedIOException("Could not move the uploaded file into place", e);
        }
    }

    private static boolean matchesMagic(Path file, String contentType) {
        byte[] head = new byte[12];
        int n;
        try (InputStream in = Files.newInputStream(file)) {
            n = in.readNBytes(head, 0, head.length);
        } catch (IOException e) {
            return false;
        }
        return StoreFileRules.matchesMagic(head, n, contentType);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always present", e);
        }
    }

    static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("Could not delete store file {}", path, e);
        }
    }
}
