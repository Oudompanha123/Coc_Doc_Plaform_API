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
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

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

    private static final Logger log = LoggerFactory.getLogger(S3AvatarStorage.class);

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

    /**
     * How long a presigned photo URL stays valid (ADR-101).
     *
     * <p>Long enough that a page which renders a dozen avatars does not have
     * half of them expire while it loads, and short enough that a URL copied
     * out of devtools stops working the same afternoon. The redirect that
     * issues it is cached for less than this, so a browser never holds a
     * redirect to an already-dead signature.
     */
    private static final java.time.Duration PRESIGN_TTL = java.time.Duration.ofMinutes(15);

    private final S3Client client;
    private final S3Presigner presigner;
    private final String bucket;
    private final String publicBaseUrl;

    public S3AvatarStorage(S3Client client, S3Presigner presigner, String bucket, String publicBaseUrl) {
        this.client = client;
        this.presigner = presigner;
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
            // The service answered and refused: wrong bucket, bad credentials,
            // denied by policy, wrong region.
            //
            // LOGGED, because without this the refusal was invisible. The
            // caller got "please try again" and the server recorded nothing at
            // all, so the one fact that identifies the problem -- AccessDenied
            // vs NoSuchBucket vs PermanentRedirect, each with a different fix
            // -- was discarded at the moment it arrived. The message stays
            // vague for the caller on purpose; the detail belongs in the log,
            // not in an HTTP body.
            log.error(
                    "avatar upload refused by object storage: code={} status={} bucket={} endpoint={}",
                    e.awsErrorDetails() == null ? "?" : e.awsErrorDetails().errorCode(),
                    e.statusCode(),
                    bucket,
                    publicBaseUrl,
                    e);
            throw ApiException.badRequest("photo upload failed — please try again");
        } catch (SdkException e) {
            // The service was never reached at all -- an endpoint pointing at
            // nothing, DNS, a firewall. SdkClientException is NOT an
            // S3Exception, so without this it escaped as an unhandled
            // exception and the caller got "an unexpected error occurred",
            // which says nothing and sent somebody looking at their photo
            // instead of at app.storage.endpoint.
            log.error("avatar upload could not reach object storage at {}", publicBaseUrl, e);
            throw ApiException.badRequest(
                    "photo upload is not working: this deployment cannot reach its object storage");
        }

        return publicBaseUrl + "/" + bucket + "/" + key;
    }

    /**
     * A signed GET for one object, valid for {@link #PRESIGN_TTL}.
     *
     * <p>Empty rather than throwing when the signer refuses: a photo that
     * cannot be signed should fall back to initials, not fail the request that
     * was rendering a page around it.
     */
    @Override
    public java.util.Optional<String> presign(String key) {
        if (presigner == null || key == null || key.isBlank()) {
            return java.util.Optional.empty();
        }
        try {
            return java.util.Optional.of(presigner
                    .presignGetObject(GetObjectPresignRequest.builder()
                            .signatureDuration(PRESIGN_TTL)
                            .getObjectRequest(GetObjectRequest.builder()
                                    .bucket(bucket)
                                    .key(key)
                                    .build())
                            .build())
                    .url()
                    .toString());
        } catch (RuntimeException e) {
            return java.util.Optional.empty();
        }
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
        } catch (SdkException e) {
            // Best-effort, and now for an unreachable endpoint as well as a
            // refusal: the account row is the source of truth, and a dangling
            // object left behind costs storage, not correctness. Removing a
            // photo must not fail because storage is misconfigured.
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
