package com.coc.docs.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.coc.docs.web.ApiException;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * No real S3 endpoint — {@link S3Client} is mocked throughout, and the point
 * of this suite is what happens before a request ever reaches it: an upload
 * that fails validation must never touch storage at all.
 *
 * <p>// req: FR-AUTH-005, NFR-SEC-PII, ADR-026
 */
class S3AvatarStorageTest {

    private static final UUID ACCOUNT_ID = UUID.fromString("0f8b1a2c-1111-4444-8888-aaaaaaaaaaaa");

    private static byte[] pngBytes() {
        try {
            BufferedImage image = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void rejectsAnEmptyFile() {
        S3AvatarStorage storage = new S3AvatarStorage(Mockito.mock(S3Client.class), null, "bucket", "https://s3.test");
        MockMultipartFile empty = new MockMultipartFile("file", "photo.png", "image/png", new byte[0]);

        assertThatThrownBy(() -> storage.upload(ACCOUNT_ID, empty))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void rejectsAFileOverTheSizeLimit() {
        S3AvatarStorage storage = new S3AvatarStorage(Mockito.mock(S3Client.class), null, "bucket", "https://s3.test");
        byte[] tooLarge = new byte[(int) S3AvatarStorage.MAX_BYTES + 1];
        MockMultipartFile file = new MockMultipartFile("file", "photo.png", "image/png", tooLarge);

        assertThatThrownBy(() -> storage.upload(ACCOUNT_ID, file))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("too large");
    }

    @Test
    void rejectsAnUnsupportedContentType() {
        S3AvatarStorage storage = new S3AvatarStorage(Mockito.mock(S3Client.class), null, "bucket", "https://s3.test");
        MockMultipartFile file = new MockMultipartFile(
                "file", "photo.svg", "image/svg+xml", pngBytes());

        assertThatThrownBy(() -> storage.upload(ACCOUNT_ID, file))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("PNG, JPEG, or WebP");
    }

    /**
     * A renamed non-image claiming to be a photo. The content type in the
     * request is not trusted past this check — only bytes that actually
     * decode as a raster image are accepted.
     */
    @Test
    void rejectsBytesThatAreNotActuallyAnImage() {
        S3AvatarStorage storage = new S3AvatarStorage(Mockito.mock(S3Client.class), null, "bucket", "https://s3.test");
        MockMultipartFile file = new MockMultipartFile(
                "file", "photo.png", "image/png", "<script>alert(1)</script>".getBytes());

        assertThatThrownBy(() -> storage.upload(ACCOUNT_ID, file))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("not a readable image");
    }

    @Test
    void neverCallsStorageForAnInvalidUpload() {
        S3Client client = Mockito.mock(S3Client.class);
        S3AvatarStorage storage = new S3AvatarStorage(client, null, "bucket", "https://s3.test");
        MockMultipartFile badFile = new MockMultipartFile("file", "photo.png", "image/png", new byte[0]);

        assertThatThrownBy(() -> storage.upload(ACCOUNT_ID, badFile)).isInstanceOf(ApiException.class);

        verify(client, never()).putObject(any(PutObjectRequest.class), any(software.amazon.awssdk.core.sync.RequestBody.class));
    }

    @Test
    void uploadsAValidPhotoAndReturnsAUrlUnderTheAccountsOwnPrefix() {
        S3Client client = Mockito.mock(S3Client.class);
        S3AvatarStorage storage = new S3AvatarStorage(client, null, "bucket", "https://s3.test");
        MockMultipartFile file = new MockMultipartFile("file", "photo.png", "image/png", pngBytes());

        String url = storage.upload(ACCOUNT_ID, file);

        assertThat(url)
                .startsWith("https://s3.test/bucket/avatars/" + ACCOUNT_ID + "/")
                .endsWith(".png");
        verify(client).putObject(any(PutObjectRequest.class), any(software.amazon.awssdk.core.sync.RequestBody.class));
    }

    @Test
    void deleteIsANoOpForAUrlThisStorageDidNotCreate() {
        S3Client client = Mockito.mock(S3Client.class);
        S3AvatarStorage storage = new S3AvatarStorage(client, null, "bucket", "https://s3.test");

        // The identity provider's own picture URL — never one of ours.
        storage.delete("https://lh3.googleusercontent.com/a/example");

        verify(client, never()).deleteObject(any(DeleteObjectRequest.class));
    }

    @Test
    void deleteRemovesAnObjectThisStorageCreated() {
        S3Client client = Mockito.mock(S3Client.class);
        S3AvatarStorage storage = new S3AvatarStorage(client, null, "bucket", "https://s3.test");

        storage.delete("https://s3.test/bucket/avatars/" + ACCOUNT_ID + "/some-uuid.png");

        verify(client).deleteObject(any(DeleteObjectRequest.class));
    }
}
