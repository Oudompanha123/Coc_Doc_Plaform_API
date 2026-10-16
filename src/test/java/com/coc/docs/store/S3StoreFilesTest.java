package com.coc.docs.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.coc.docs.web.ApiException;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;
import software.amazon.awssdk.services.s3.model.UploadPartResponse;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * {@link S3StoreFiles} against a mocked {@link S3Client}, with a real
 * {@link S3Presigner} (presigning is local: no network, no AWS account).
 * Parts are 5 bytes here so multipart is exercised with a dozen bytes.
 *
 * <p>// req: ADR-115
 */
class S3StoreFilesTest {

    private static final UUID APP = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final String KEY_PROD = "android/1.1.2/production/" + APP + "/app.apk";
    private static final String KEY_DEV = "android/1.1.2/development/" + APP + "/app.apk";

    private S3Client s3;
    private S3Presigner presigner;

    @BeforeEach
    void setUp() {
        s3 = mock(S3Client.class);
        // Nothing exists in the bucket until a test says so.
        when(s3.headObject(any(HeadObjectRequest.class))).thenThrow(NoSuchKeyException.builder().build());
        when(s3.createMultipartUpload(any(CreateMultipartUploadRequest.class)))
                .thenReturn(CreateMultipartUploadResponse.builder().uploadId("up-1").build());
        when(s3.uploadPart(any(UploadPartRequest.class), any(RequestBody.class)))
                .thenReturn(UploadPartResponse.builder().eTag("etag").build());
        presigner = S3Presigner.builder()
                .endpointOverride(URI.create("https://s3.ap-southeast-2.amazonaws.com"))
                .region(Region.AP_SOUTHEAST_2)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("AKIATEST", "secret")))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build();
    }

    private S3StoreFiles files(long max) {
        return new S3StoreFiles(s3, presigner, "coc-docs-media", max, Duration.ofMinutes(5), 5);
    }

    private static ByteArrayInputStream bytes(int n) {
        byte[] data = new byte[n];
        for (int i = 0; i < n; i++) {
            data[i] = (byte) ('a' + i % 26);
        }
        return new ByteArrayInputStream(data);
    }

    private static String sha256Of(int n) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes(n).readAllBytes()));
    }

    private StoreFiles.StoredBuild store(S3StoreFiles files, List<StoreEnvironment> envs, int size) {
        return files.storeBuild(StorePlatform.ANDROID, "1.1.2", envs, APP, "app.apk", bytes(size), env -> false);
    }

    // --- uploads ----------------------------------------------------------------

    @Test
    void aFileThatFitsOnePartIsOnePutObject() throws Exception {
        StoreFiles.StoredBuild stored = store(files(100), List.of(StoreEnvironment.PRODUCTION), 4);

        ArgumentCaptor<PutObjectRequest> put = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3).putObject(put.capture(), any(RequestBody.class));
        assertThat(put.getValue().bucket()).isEqualTo("coc-docs-media");
        assertThat(put.getValue().key()).isEqualTo(KEY_PROD);
        verify(s3, never()).createMultipartUpload(any(CreateMultipartUploadRequest.class));
        assertThat(stored.paths().get(StoreEnvironment.PRODUCTION)).isEqualTo(KEY_PROD);
        assertThat(stored.sizeBytes()).isEqualTo(4);
        assertThat(stored.sha256()).isEqualTo(sha256Of(4));
    }

    @Test
    void aLargerFileIsAMultipartUploadWithTheRightSizeAndHash() throws Exception {
        StoreFiles.StoredBuild stored = store(files(100), List.of(StoreEnvironment.PRODUCTION), 12);

        verify(s3, times(3)).uploadPart(any(UploadPartRequest.class), any(RequestBody.class));
        ArgumentCaptor<CompleteMultipartUploadRequest> done = ArgumentCaptor.forClass(CompleteMultipartUploadRequest.class);
        verify(s3).completeMultipartUpload(done.capture());
        assertThat(done.getValue().multipartUpload().parts()).hasSize(3);
        assertThat(done.getValue().uploadId()).isEqualTo("up-1");
        verify(s3, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
        assertThat(stored.sizeBytes()).isEqualTo(12);
        assertThat(stored.sha256()).isEqualTo(sha256Of(12));
    }

    @Test
    void aFileThatIsAnExactMultipleOfThePartSizeSendsNoEmptyPart() throws Exception {
        StoreFiles.StoredBuild stored = store(files(100), List.of(StoreEnvironment.PRODUCTION), 10);

        verify(s3, times(2)).uploadPart(any(UploadPartRequest.class), any(RequestBody.class));
        verify(s3).completeMultipartUpload(any(CompleteMultipartUploadRequest.class));
        assertThat(stored.sizeBytes()).isEqualTo(10);
        assertThat(stored.sha256()).isEqualTo(sha256Of(10));
    }

    @Test
    void anOversizedFileIsRefusedAndItsMultipartUploadAborted() {
        assertThatThrownBy(() -> store(files(8), List.of(StoreEnvironment.PRODUCTION), 12))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE));

        verify(s3).abortMultipartUpload(any(AbortMultipartUploadRequest.class));
        verify(s3, never()).completeMultipartUpload(any(CompleteMultipartUploadRequest.class));
    }

    @Test
    void anEmptyFileIsRefusedAndNothingIsWritten() {
        assertThatThrownBy(() -> store(files(100), List.of(StoreEnvironment.PRODUCTION), 0))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST));

        verify(s3, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
        verify(s3, never()).createMultipartUpload(any(CreateMultipartUploadRequest.class));
    }

    @Test
    void publishingToBothEnvironmentsUploadsOnceAndCopiesServerSide() {
        StoreFiles.StoredBuild stored =
                store(files(100), List.of(StoreEnvironment.PRODUCTION, StoreEnvironment.DEVELOPMENT), 4);

        verify(s3, times(1)).putObject(any(PutObjectRequest.class), any(RequestBody.class));
        ArgumentCaptor<CopyObjectRequest> copy = ArgumentCaptor.forClass(CopyObjectRequest.class);
        verify(s3).copyObject(copy.capture());
        assertThat(copy.getValue().sourceKey()).isEqualTo(KEY_PROD);
        assertThat(copy.getValue().destinationKey()).isEqualTo(KEY_DEV);
        assertThat(stored.paths()).containsEntry(StoreEnvironment.DEVELOPMENT, KEY_DEV);
    }

    @Test
    void aFailedCopyRemovesWhatWasAlreadyPlaced() {
        when(s3.copyObject(any(CopyObjectRequest.class))).thenThrow(new IllegalStateException("boom"));

        assertThatThrownBy(() -> store(files(100), List.of(StoreEnvironment.PRODUCTION, StoreEnvironment.DEVELOPMENT), 4))
                .isInstanceOf(IllegalStateException.class);

        ArgumentCaptor<DeleteObjectRequest> delete = ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(s3).deleteObject(delete.capture());
        assertThat(delete.getValue().key()).isEqualTo(KEY_PROD);
    }

    @Test
    void anExistingBuildIsKeptUnlessStale() {
        when(s3.headObject(any(HeadObjectRequest.class)))
                .thenReturn(software.amazon.awssdk.services.s3.model.HeadObjectResponse.builder().build());

        assertThatThrownBy(() -> store(files(100), List.of(StoreEnvironment.PRODUCTION), 4))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT));
        verify(s3, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));

        files(100).storeBuild(StorePlatform.ANDROID, "1.1.2", List.of(StoreEnvironment.PRODUCTION), APP, "app.apk",
                bytes(4), env -> true);
        verify(s3).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    void aVersionThatCouldEscapeTheKeyIsRefused() {
        assertThatThrownBy(() -> files(100).storeBuild(StorePlatform.ANDROID, "../1", List.of(StoreEnvironment.PRODUCTION),
                APP, "app.apk", bytes(4), env -> false))
                .isInstanceOf(ApiException.class);
        verifyNoMoreInteractions(s3);
    }

    // --- downloads --------------------------------------------------------------

    @Test
    void aDownloadIsAShortLivedAttachmentUrl() {
        when(s3.headObject(any(HeadObjectRequest.class)))
                .thenReturn(software.amazon.awssdk.services.s3.model.HeadObjectResponse.builder().build());

        StoreFiles.Delivery delivery = files(100).deliver(KEY_PROD, "my app.apk", "application/vnd.android.package-archive");

        assertThat(delivery).isInstanceOf(StoreFiles.Delivery.Presigned.class);
        URI url = ((StoreFiles.Delivery.Presigned) delivery).url();
        String text = url.toString();
        assertThat(url.getHost()).isEqualTo("s3.ap-southeast-2.amazonaws.com");
        assertThat(url.getPath()).isEqualTo("/coc-docs-media/" + KEY_PROD);
        assertThat(text).contains("X-Amz-Expires=300");
        assertThat(java.net.URLDecoder.decode(text, StandardCharsets.UTF_8)).contains("attachment");
        assertThat(text).doesNotContain("secret");
    }

    @Test
    void aBuildWhoseObjectIsMissingIsNotFound() {
        assertThatThrownBy(() -> files(100).deliver(KEY_PROD, "app.apk", "application/octet-stream"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    // --- delete and logos -------------------------------------------------------

    @Test
    void deleteRemovesOnlyThatObject() {
        files(100).delete(KEY_PROD);

        ArgumentCaptor<DeleteObjectRequest> delete = ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(s3).deleteObject(delete.capture());
        assertThat(delete.getValue().key()).isEqualTo(KEY_PROD);
    }

    @Test
    void aLogoMustReallyBeTheImageItClaims() {
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0};
        StoreFiles.StoredFile stored = files(100).storeLogo(APP, "image/png", new ByteArrayInputStream(png));

        assertThat(stored.relativePath()).isEqualTo("logos/" + APP + ".png");
        // The other two extensions are cleared so a replaced logo leaves nothing behind.
        verify(s3, times(2)).deleteObject(any(DeleteObjectRequest.class));

        assertThatThrownBy(() -> files(100).storeLogo(APP, "image/png", new ByteArrayInputStream(new byte[] {1, 2, 3})))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> files(100).storeLogo(APP, "image/svg+xml", new ByteArrayInputStream(png)))
                .isInstanceOf(ApiException.class);
    }
}
