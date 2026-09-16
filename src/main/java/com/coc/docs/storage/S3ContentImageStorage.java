package com.coc.docs.storage;

import com.coc.docs.web.ApiException;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * Stores a docs-content image behind the S3 API (CONST-TECH-08), same
 * validation rules as {@link com.coc.docs.storage.S3AvatarStorage} (2 MB
 * max, PNG/JPEG/WebP, and the bytes must actually decode as an image — a
 * declared Content-Type is never trusted alone) but under its own key
 * prefix, since these aren't tied to one account.
 */
public class S3ContentImageStorage implements ContentImageStorage {

    static final long MAX_BYTES = 2L * 1024 * 1024;

    private static final Map<String, String> ALLOWED_TYPES =
            Map.of("image/png", "png", "image/jpeg", "jpg", "image/webp", "webp");

    private final S3Client client;
    private final String bucket;
    private final String publicBaseUrl;

    public S3ContentImageStorage(S3Client client, String bucket, String publicBaseUrl) {
        this.client = client;
        this.bucket = bucket;
        this.publicBaseUrl = publicBaseUrl.endsWith("/")
                ? publicBaseUrl.substring(0, publicBaseUrl.length() - 1)
                : publicBaseUrl;
    }

    @Override
    public String upload(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw ApiException.badRequest("choose an image to upload");
        }
        if (file.getSize() > MAX_BYTES) {
            throw ApiException.badRequest("that image is too large — please choose one under 2 MB");
        }
        String extension = ALLOWED_TYPES.get(file.getContentType());
        if (extension == null) {
            throw ApiException.badRequest("images must be PNG, JPEG, or WebP");
        }

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw ApiException.badRequest("that file could not be read — please try again");
        }

        BufferedImage decoded;
        try {
            decoded = ImageIO.read(new ByteArrayInputStream(bytes));
        } catch (IOException e) {
            decoded = null;
        }
        if (decoded == null) {
            throw ApiException.badRequest("that file is not a readable image");
        }

        String key = "content-images/" + UUID.randomUUID() + "." + extension;
        try {
            client.putObject(
                    PutObjectRequest.builder()
                            .bucket(bucket)
                            .key(key)
                            .contentType(file.getContentType())
                            .build(),
                    RequestBody.fromBytes(bytes));
        } catch (S3Exception e) {
            throw ApiException.badRequest("image upload failed — please try again");
        }

        return publicBaseUrl + "/" + bucket + "/" + key;
    }
}
