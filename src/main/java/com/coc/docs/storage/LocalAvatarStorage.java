package com.coc.docs.storage;

import com.coc.docs.account.AvatarStorage;
import com.coc.docs.web.ApiException;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.multipart.MultipartFile;

/**
 * Keeps a self-uploaded profile photo on this backend's own disk, under
 * {@code file.server-path} (ADR-119) — used when no object storage is
 * configured (see {@link AvatarStorageConfig}).
 *
 * <p>What {@link #upload} returns, and so what {@code account.avatar_url}
 * holds, is the <b>file name only</b> ({@code <uuid>.png}) — never a path or
 * a URL. {@code ApiDtos.avatarHref} turns it into {@code /api/v1/image/<name>}
 * for the browser, and {@code ImageController} serves it.
 *
 * <p>The same checks as {@link S3AvatarStorage}: 2 MB, PNG/JPEG/WebP, and the
 * bytes must really decode as an image. The name is chosen here, so nothing a
 * caller sends decides where a file lands.
 *
 * <p>// req: FR-AUTH-005, NFR-SEC-PII, ADR-026, ADR-119
 */
public class LocalAvatarStorage implements AvatarStorage {

    private static final Logger log = LoggerFactory.getLogger(LocalAvatarStorage.class);

    static final long MAX_BYTES = 2L * 1024 * 1024;

    private static final Map<String, String> ALLOWED_TYPES =
            Map.of("image/png", "png", "image/jpeg", "jpg", "image/webp", "webp");

    /** The only shape this class writes — anchored, so nothing with a slash or {@code ..} is ever a stored name. */
    private static final Pattern STORED_NAME = Pattern.compile("^[0-9a-f-]{36}\\.(png|jpg|webp)$");

    private final Path directory;

    public LocalAvatarStorage(String serverPath) {
        this.directory = Path.of(serverPath.trim()).toAbsolutePath().normalize();
    }

    /** Whether {@code value} is a file name this storage wrote, as opposed to a URL (Google's, or an S3 one). */
    public static boolean isStoredName(String value) {
        return value != null && STORED_NAME.matcher(value).matches();
    }

    @Override
    public String upload(UUID accountId, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw ApiException.badRequest("choose a photo to upload");
        }
        if (file.getSize() > MAX_BYTES) {
            throw ApiException.badRequest("that photo is too large — please choose one under 2 MB");
        }
        String extension = ALLOWED_TYPES.get(file.getContentType());
        if (extension == null) {
            throw ApiException.badRequest("photos must be PNG, JPEG, or WebP");
        }

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw ApiException.badRequest("that file could not be read — please try again");
        }

        // A Content-Type header is only a claim; the bytes have to decode.
        BufferedImage decoded;
        try {
            decoded = ImageIO.read(new ByteArrayInputStream(bytes));
        } catch (IOException e) {
            decoded = null;
        }
        if (decoded == null) {
            throw ApiException.badRequest("that file is not a readable image");
        }

        String fileName = UUID.randomUUID() + "." + extension;
        try {
            Files.createDirectories(directory);
            Files.write(directory.resolve(fileName), bytes);
        } catch (IOException e) {
            // Logged with the folder, because "please try again" alone sends
            // somebody looking at their photo instead of at file.server-path.
            log.error("avatar upload could not be written under {}", directory, e);
            throw ApiException.badRequest("photo upload failed — this deployment cannot write to its upload folder");
        }
        return fileName;
    }

    @Override
    public void delete(String avatarUrl) {
        if (!isStoredName(avatarUrl)) {
            // Not one of ours (the identity provider's URL, say) — nothing here to remove.
            return;
        }
        try {
            Files.deleteIfExists(directory.resolve(avatarUrl));
        } catch (IOException e) {
            // Best effort: the account row is the source of truth.
            log.warn("Could not delete the stored photo {}", avatarUrl, e);
        }
    }

    /** The stored file for {@code fileName}, if it is one of ours and still there. */
    public Optional<Path> find(String fileName) {
        if (!isStoredName(fileName)) {
            return Optional.empty();
        }
        Path file = directory.resolve(fileName);
        return Files.isRegularFile(file) ? Optional.of(file) : Optional.empty();
    }
}
