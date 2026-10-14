package com.coc.docs.store;

import com.coc.docs.web.ApiException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * The Store's files on the backend's local disk (ADR-108):
 *
 * <pre>
 * {root}/{platform}/{version}/{environment}/{app id}/{file name}   an install file
 * {root}/logos/{app id}.{png|jpg|webp}                             an app's logo
 * </pre>
 *
 * <p>The app id level under the environment keeps two apps on the same
 * platform and version from sharing a folder — the layout the PM asked for
 * (platform, version, environment) stays the first three levels.
 *
 * <p><b>Every path is built here, from validated parts, and checked to stay
 * under the root.</b> A version is held to {@link #VERSION} and a file name is
 * reduced to {@link #safeFileName}; neither can carry a separator or "..",
 * and {@link #resolve} refuses anything that normalises outside the root
 * anyway, so a crafted value in the database could not reach another file
 * either.
 *
 * <p><b>Writes are whole or absent.</b> Bytes stream to a temporary file in
 * the destination folder — never buffered in memory, since an installer can
 * be hundreds of megabytes — and are moved into place only once the size cap
 * and the content checks have passed.
 *
 * <p>// req: ADR-108
 */
@Component
public class StoreFileStorage {

    private static final Logger log = LoggerFactory.getLogger(StoreFileStorage.class);

    /** Mirrors {@code store_app_release.version}'s check constraint (V82). */
    static final Pattern VERSION = Pattern.compile("^[0-9A-Za-z]([0-9A-Za-z._+-]{0,62}[0-9A-Za-z])?$");

    static final int MAX_FILE_NAME = 120;

    /** A logo is an icon, not a photo album. */
    static final long MAX_LOGO_BYTES = 2L * 1024 * 1024;

    /**
     * Raster only. SVG is left out on purpose: it is a document that can carry
     * script, and the logo route is public.
     */
    static final Map<String, String> LOGO_TYPES = Map.of(
            "image/png", "png",
            "image/jpeg", "jpg",
            "image/webp", "webp");

    private final Path root;
    private final long maxFileBytes;

    public StoreFileStorage(
            @Value("${coc.store.root:store-files}") String root,
            @Value("${coc.store.max-file-bytes:2147483648}") long maxFileBytes) {
        this.root = Path.of(root).toAbsolutePath().normalize();
        this.maxFileBytes = maxFileBytes;
    }

    public Path root() {
        return root;
    }

    public long maxFileBytes() {
        return maxFileBytes;
    }

    /** What was written: where (relative to the root), how big, and its SHA-256. */
    public record StoredFile(String relativePath, long sizeBytes, String sha256) {}

    public static boolean isValidVersion(String version) {
        return version != null && VERSION.matcher(version).matches();
    }

    /**
     * The uploader's file name, reduced to something every file system will
     * take as a single name: path parts dropped, anything outside a
     * conservative set replaced, no leading dot, trailing dots and spaces gone
     * (Windows strips them silently, which would make two names one).
     */
    public static String safeFileName(String original) {
        String name = original == null ? "" : original;
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        name = name.substring(slash + 1);
        name = name.replaceAll("[^0-9A-Za-z._()\\- ]", "_").trim();
        name = name.replaceAll("^[.\\s]+", "").replaceAll("[.\\s]+$", "");
        if (name.length() > MAX_FILE_NAME) {
            int dot = name.lastIndexOf('.');
            String extension = dot > 0 && name.length() - dot <= 10 ? name.substring(dot) : "";
            name = name.substring(0, MAX_FILE_NAME - extension.length()) + extension;
        }
        return name.isEmpty() ? "download" : name;
    }

    /**
     * Streams an install file to
     * {@code {platform}/{version}/{environment}/{appId}/{fileName}}.
     * {@code fileName} must already be {@link #safeFileName safe}.
     *
     * <p>An existing file at that path is never replaced blindly — it may be a
     * build someone has already downloaded. {@code existingIsStale} is asked
     * only when one is there, and answers whether it is debris (no release row
     * points at it, e.g. from a crash between the write and the insert) that
     * may be overwritten; otherwise the upload is refused with 409.
     */
    public StoredFile storeRelease(
            StorePlatform platform,
            String version,
            StoreEnvironment environment,
            UUID appId,
            String fileName,
            InputStream body,
            BooleanSupplier existingIsStale) {
        if (!isValidVersion(version)) {
            throw ApiException.badRequest("the version may use letters, digits, '.', '_', '+' and '-' only");
        }
        if (!safeFileName(fileName).equals(fileName)) {
            throw new IllegalArgumentException("file name not sanitised: " + fileName);
        }
        String relative = String.join("/", platform.wire(), version, environment.wire(), appId.toString(), fileName);
        Path target = resolve(relative);
        Written written = write(target, body, maxFileBytes, "the file is larger than the Store allows");
        if (written.size() == 0) {
            deleteQuietly(written.temp());
            throw ApiException.badRequest("the file is empty");
        }
        try {
            Files.move(written.temp(), target);
        } catch (FileAlreadyExistsException e) {
            if (!existingIsStale.getAsBoolean()) {
                deleteQuietly(written.temp());
                throw ApiException.conflict(
                        "this app already has a " + environment.wire() + " build of version " + version);
            }
            commit(written.temp(), target);
        } catch (IOException e) {
            deleteQuietly(written.temp());
            throw new UncheckedIOException("Could not move the uploaded file into place", e);
        }
        return new StoredFile(relative, written.size(), written.sha256());
    }

    /**
     * Streams a logo to {@code logos/{appId}.{ext}}, checking that the bytes
     * really are the image type claimed — the logo route is public and sends
     * that type back, so a mislabelled upload must not get through.
     */
    public StoredFile storeLogo(UUID appId, String contentType, InputStream body) {
        String extension = LOGO_TYPES.get(contentType);
        if (extension == null) {
            throw ApiException.badRequest("a logo must be a PNG, JPEG or WebP image");
        }
        String relative = "logos/" + appId + "." + extension;
        Path target = resolve(relative);
        Written written = write(target, body, MAX_LOGO_BYTES, "a logo must be 2 MB or smaller");
        try {
            if (!matchesMagic(written.temp(), contentType)) {
                throw ApiException.badRequest("the logo's content is not the image type it claims to be");
            }
        } catch (ApiException e) {
            deleteQuietly(written.temp());
            throw e;
        }
        commit(written.temp(), target);
        // A logo replaced by one of another type leaves its old file behind otherwise.
        for (String other : LOGO_TYPES.values()) {
            if (!other.equals(extension)) {
                deleteQuietly(resolve("logos/" + appId + "." + other));
            }
        }
        return new StoredFile(relative, written.size(), written.sha256());
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
     * including) the root — so deleting the last build of a version does not
     * leave an empty {@code android/1.2.0/production/} tree behind.
     */
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
        return switch (contentType) {
            case "image/png" -> n >= 8
                    && (head[0] & 0xFF) == 0x89 && head[1] == 'P' && head[2] == 'N' && head[3] == 'G';
            case "image/jpeg" -> n >= 3
                    && (head[0] & 0xFF) == 0xFF && (head[1] & 0xFF) == 0xD8 && (head[2] & 0xFF) == 0xFF;
            case "image/webp" -> n >= 12
                    && head[0] == 'R' && head[1] == 'I' && head[2] == 'F' && head[3] == 'F'
                    && head[8] == 'W' && head[9] == 'E' && head[10] == 'B' && head[11] == 'P';
            default -> false;
        };
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
