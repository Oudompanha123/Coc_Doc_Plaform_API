package com.coc.docs.store;

import com.coc.docs.web.ApiException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpStatus;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

/**
 * The Store's files in S3 (ADR-115): the same keys as the local backend —
 * {@code {platform}/{version}/{environment}/{appId}/{fileName}} and {@code
 * logos/{appId}.{ext}} — in the bucket the rest of the app already uses
 * ({@code S3_*}).
 *
 * <p><b>Uploads stream; nothing is held but one part.</b> A build can be 2 GB,
 * so it is read in {@value #DEFAULT_PART_BYTES}-byte parts and sent as an S3
 * multipart upload, hashing as it goes. A file that fits in one part is a
 * single {@code PutObject}. The size cap is enforced while reading, and a
 * failed or oversized upload aborts the multipart upload, so no half-written
 * object — and no billed orphan parts — is left behind.
 *
 * <p><b>Two environments, one upload.</b> A file published to Production and
 * Development is sent once and the second key is a server-side copy, so each
 * build owns its own object and can be removed without touching the other.
 *
 * <p><b>Downloads never pass through the backend.</b> {@link #deliver} checks
 * the object exists and returns a presigned GET that expires in minutes and
 * forces an attachment download. The access decision — public app, admin,
 * not removed — is made by {@code StoreService} before this is called.
 *
 * <p>// req: ADR-115, CONST-TECH-08
 */
public class S3StoreFiles implements StoreFiles {

    private static final Logger log = LoggerFactory.getLogger(S3StoreFiles.class);

    /** S3's minimum part size is 5 MiB; 8 MiB keeps a 2 GB build to ~250 parts, well under the 10,000 limit. */
    static final int DEFAULT_PART_BYTES = 8 * 1024 * 1024;

    private static final String BINARY = "application/octet-stream";

    private final S3Client s3;
    private final S3Presigner presigner;
    private final String bucket;
    private final long maxFileBytes;
    private final Duration downloadTtl;
    private final int partBytes;

    public S3StoreFiles(S3Client s3, S3Presigner presigner, String bucket, long maxFileBytes, Duration downloadTtl) {
        this(s3, presigner, bucket, maxFileBytes, downloadTtl, DEFAULT_PART_BYTES);
    }

    /** Package-private so a test can use small parts. */
    S3StoreFiles(
            S3Client s3, S3Presigner presigner, String bucket, long maxFileBytes, Duration downloadTtl, int partBytes) {
        this.s3 = s3;
        this.presigner = presigner;
        this.bucket = bucket;
        this.maxFileBytes = maxFileBytes;
        this.downloadTtl = downloadTtl;
        this.partBytes = partBytes;
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
        Map<StoreEnvironment, String> keys = new LinkedHashMap<>();
        for (StoreEnvironment environment : environments) {
            String key = StoreFileRules.buildKey(platform, version, environment, appId, fileName);
            if (exists(key) && !existingIsStale.test(environment)) {
                throw ApiException.conflict("this app already has a " + platform.wire() + " " + environment.wire()
                        + " build of version " + version);
            }
            keys.put(environment, key);
        }

        String first = keys.values().iterator().next();
        Uploaded uploaded = upload(first, body);
        List<String> placed = new ArrayList<>(List.of(first));
        try {
            for (String key : keys.values()) {
                if (key.equals(first)) {
                    continue;
                }
                s3.copyObject(CopyObjectRequest.builder()
                        .sourceBucket(bucket)
                        .sourceKey(first)
                        .destinationBucket(bucket)
                        .destinationKey(key)
                        .contentType(BINARY)
                        .build());
                placed.add(key);
            }
        } catch (RuntimeException e) {
            placed.forEach(this::deleteQuietly);
            throw e;
        }
        return new StoredBuild(keys, uploaded.size(), uploaded.sha256());
    }

    private record Uploaded(long size, String sha256) {}

    /** Streams {@code body} to {@code key}: one PutObject if it fits one part, else a multipart upload. */
    private Uploaded upload(String key, InputStream body) {
        MessageDigest sha256 = sha256();
        byte[] part = new byte[partBytes];
        long size = 0;
        String uploadId = null;
        List<CompletedPart> completed = new ArrayList<>();
        boolean putWhole = false;
        try {
            while (true) {
                int n = readFully(body, part);
                if (n == 0 && !completed.isEmpty()) {
                    break; // the file was an exact multiple of the part size
                }
                size += n;
                if (size > maxFileBytes) {
                    throw ApiException.withCode(
                            HttpStatus.PAYLOAD_TOO_LARGE, "too_large", "the file is larger than the Store allows");
                }
                sha256.update(part, 0, n);
                boolean last = n < part.length;
                if (uploadId == null && last) {
                    if (n == 0) {
                        throw ApiException.badRequest("the file is empty");
                    }
                    s3.putObject(
                            PutObjectRequest.builder().bucket(bucket).key(key).contentType(BINARY).build(),
                            RequestBody.fromByteBuffer(ByteBuffer.wrap(part, 0, n)));
                    putWhole = true;
                    break;
                }
                if (uploadId == null) {
                    uploadId = s3.createMultipartUpload(CreateMultipartUploadRequest.builder()
                                    .bucket(bucket)
                                    .key(key)
                                    .contentType(BINARY)
                                    .build())
                            .uploadId();
                }
                int number = completed.size() + 1;
                String etag = s3.uploadPart(
                                UploadPartRequest.builder()
                                        .bucket(bucket)
                                        .key(key)
                                        .uploadId(uploadId)
                                        .partNumber(number)
                                        .build(),
                                RequestBody.fromByteBuffer(ByteBuffer.wrap(part, 0, n)))
                        .eTag();
                completed.add(CompletedPart.builder().partNumber(number).eTag(etag).build());
                if (last) {
                    break;
                }
            }
            if (uploadId != null) {
                s3.completeMultipartUpload(CompleteMultipartUploadRequest.builder()
                        .bucket(bucket)
                        .key(key)
                        .uploadId(uploadId)
                        .multipartUpload(CompletedMultipartUpload.builder().parts(completed).build())
                        .build());
            }
        } catch (RuntimeException e) {
            if (uploadId != null) {
                abort(key, uploadId);
            }
            if (putWhole) {
                deleteQuietly(key);
            }
            throw e;
        }
        return new Uploaded(size, HexFormat.of().formatHex(sha256.digest()));
    }

    private static int readFully(InputStream in, byte[] buffer) {
        try {
            return in.readNBytes(buffer, 0, buffer.length);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the uploaded file", e);
        }
    }

    private void abort(String key, String uploadId) {
        try {
            s3.abortMultipartUpload(
                    AbortMultipartUploadRequest.builder().bucket(bucket).key(key).uploadId(uploadId).build());
        } catch (RuntimeException e) {
            log.warn("Could not abort multipart upload {} of {}; the bucket's lifecycle rule will clear it", uploadId, key, e);
        }
    }

    @Override
    public StoredFile storeLogo(UUID appId, String contentType, InputStream body) {
        String extension = StoreFileRules.LOGO_TYPES.get(contentType);
        if (extension == null) {
            throw ApiException.badRequest("a logo must be a PNG, JPEG or WebP image");
        }
        byte[] bytes;
        try {
            bytes = body.readNBytes((int) StoreFileRules.MAX_LOGO_BYTES + 1);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the uploaded logo", e);
        }
        if (bytes.length > StoreFileRules.MAX_LOGO_BYTES) {
            throw ApiException.withCode(HttpStatus.PAYLOAD_TOO_LARGE, "too_large", "a logo must be 2 MB or smaller");
        }
        if (!StoreFileRules.matchesMagic(bytes, Math.min(bytes.length, 12), contentType)) {
            throw ApiException.badRequest("the logo's content is not the image type it claims to be");
        }
        String key = StoreFileRules.logoKey(appId, extension);
        s3.putObject(
                PutObjectRequest.builder().bucket(bucket).key(key).contentType(contentType).build(),
                RequestBody.fromBytes(bytes));
        // A logo replaced by one of another type leaves its old object behind otherwise.
        for (String other : StoreFileRules.LOGO_TYPES.values()) {
            if (!other.equals(extension)) {
                deleteQuietly(StoreFileRules.logoKey(appId, other));
            }
        }
        return new StoredFile(key, bytes.length);
    }

    @Override
    public void delete(String relativePath) {
        // Deleting a key that is not there succeeds in S3, so a missing file is not an error.
        s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(relativePath).build());
    }

    @Override
    public Delivery deliver(String relativePath, String fileName, String contentType) {
        if (!exists(relativePath)) {
            throw ApiException.notFound("build file");
        }
        GetObjectRequest get = GetObjectRequest.builder()
                .bucket(bucket)
                .key(relativePath)
                // Always a download, never rendered in the browser, named as the uploader named it.
                .responseContentDisposition(
                        ContentDisposition.attachment().filename(fileName, StandardCharsets.UTF_8).build().toString())
                .responseContentType(contentType)
                .build();
        PresignedGetObjectRequest presigned = presigner.presignGetObject(
                GetObjectPresignRequest.builder().signatureDuration(downloadTtl).getObjectRequest(get).build());
        try {
            return new Delivery.Presigned(presigned.url().toURI(), presigned.expiration());
        } catch (URISyntaxException e) {
            throw new IllegalStateException("S3 produced a malformed presigned URL", e);
        }
    }

    @Override
    public Resource openLogo(String relativePath) {
        try {
            return new ByteArrayResource(s3.getObjectAsBytes(
                            GetObjectRequest.builder().bucket(bucket).key(relativePath).build())
                    .asByteArray());
        } catch (NoSuchKeyException e) {
            throw ApiException.notFound("logo");
        }
    }

    private boolean exists(String key) {
        try {
            s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return false;
            }
            throw e;
        }
    }

    private void deleteQuietly(String key) {
        try {
            delete(key);
        } catch (RuntimeException e) {
            log.warn("Could not delete store object {}", key, e);
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always present", e);
        }
    }
}
