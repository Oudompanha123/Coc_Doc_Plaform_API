package com.coc.docs.storage;

import com.coc.docs.account.AvatarStorage;
import com.coc.docs.web.ApiException;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * Stores a self-uploaded profile photo behind the S3 API (CONST-TECH-08,
 * ADR-026) — MinIO locally, whichever S3-compatible provider is chosen once
 * backend hosting is decided (ADR-012 §2 — still {@code [DEFERRED]}). Nothing
 * here names a vendor; {@link AvatarStorageConfig} is the only place that
 * builds the client, from {@code app.storage.*}.
 *
 * <p>Only ever exists for a photo an account owner chose to upload — never
 * for the identity-provider {@code picture} URL {@code
 * KeycloakIdTokenService} captures, which is never fetched or re-hosted by
 * this system (V5__account_avatar.sql).
 */
public class S3AvatarStorage implements AvatarStorage {

    /** Generous enough for a face photo, small enough that an abusive upload costs little. */
    static final long MAX_BYTES = 2L * 1024 * 1024;

    /**
     * Allow-listed by content, not by what the browser claimed: {@link
     * #upload} re-decodes the bytes with {@link ImageIO} before trusting
     * either this map or the caller's {@code Content-Type} header, so a
     * renamed script cannot ride through as an image.
     */
    private static final Map<String, String> ALLOWED_TYPES =
            Map.of("image/png", "png", "image/jpeg", "jpg", "image/webp", "webp");

    private final S3Client client;
    private final String bucket;
    private final String publicBaseUrl;

    public S3AvatarStorage(S3Client client, String bucket, String publicBaseUrl) {
        this.client = client;
        this.bucket = bucket;
        // No trailing slash, so key-joining below never doubles one up.
        this.publicBaseUrl = publicBaseUrl.endsWith("/")
                ? publicBaseUrl.substring(0, publicBaseUrl.length() - 1)
                : publicBaseUrl;
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

        // The check that actually matters: a Content-Type header is whatever
        // the browser sent, and this system does not trust it any further
        // than a claim. Only bytes that genuinely decode as a raster image
        // reach object storage.
        BufferedImage decoded;
        try {
            decoded = ImageIO.read(new ByteArrayInputStream(bytes));
        } catch (IOException e) {
            decoded = null;
        }
        if (decoded == null) {
            throw ApiException.badRequest("that file is not a readable image");
        }

        String key = "avatars/" + accountId + "/" + UUID.randomUUID() + "." + extension;
        try {
            client.putObject(
                    PutObjectRequest.builder()
                            .bucket(bucket)
                            .key(key)
                            .contentType(file.getContentType())
                            .build(),
                    RequestBody.fromBytes(bytes));
        } catch (S3Exception e) {
            throw ApiException.badRequest("photo upload failed — please try again");
        }

        return publicBaseUrl + "/" + bucket + "/" + key;
    }

    @Override
    public void delete(String avatarUrl) {
        String key = keyOf(avatarUrl);
        if (key == null) {
            // Not one of ours (e.g. still the identity provider's URL) —
            // nothing here to remove.
            return;
        }
        try {
            client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
        } catch (S3Exception e) {
            // Best-effort: the account row is the source of truth, and a
            // dangling object left behind costs storage, not correctness.
        }
    }

    private String keyOf(String avatarUrl) {
        String prefix = publicBaseUrl + "/" + bucket + "/";
        if (avatarUrl == null || !avatarUrl.startsWith(prefix)) {
            return null;
        }
        return avatarUrl.substring(prefix.length());
    }

    /** Recognised photo content types, for anything that wants to validate client-side too. */
    public static Set<String> allowedContentTypes() {
        return ALLOWED_TYPES.keySet();
    }
}
