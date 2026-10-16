package com.coc.docs.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.coc.docs.config.AppProperties;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Which backend the Store gets (ADR-115): local unless S3 is chosen outright,
 * and a clear startup failure if S3 is chosen without a bucket.
 *
 * <p>// req: ADR-115
 */
class StoreFilesConfigTest {

    private final StoreFilesConfig config = new StoreFilesConfig();

    private static AppProperties props(String endpoint, String bucket, String key, String secret) {
        return new AppProperties(Duration.ofDays(1), "http://localhost:3000", true, "", "",
                new AppProperties.Storage(endpoint, bucket, "ap-southeast-2", key, secret));
    }

    private StoreFiles build(AppProperties properties, String backend) {
        return config.storeFiles(properties, backend, "target/store-config-test", 1024, 300);
    }

    @Test
    void localIsTheDefaultEvenWhenS3IsConfiguredForAvatars() {
        AppProperties withS3 = props("https://s3.ap-southeast-2.amazonaws.com", "coc-docs-media", "k", "s");
        assertThat(build(withS3, "local")).isInstanceOf(StoreFileStorage.class);
    }

    @Test
    void s3IsChosenOnlyWhenAskedFor() {
        AppProperties withS3 = props("https://s3.ap-southeast-2.amazonaws.com", "coc-docs-media", "k", "s");
        assertThat(build(withS3, "S3")).isInstanceOf(S3StoreFiles.class);
    }

    @Test
    void s3RunsOnAnInstanceRoleWithNoKeys() {
        AppProperties noKeys = props("https://s3.ap-southeast-2.amazonaws.com", "coc-docs-media", "", "");
        assertThat(build(noKeys, "s3")).isInstanceOf(S3StoreFiles.class);
    }

    @Test
    void s3WithoutABucketFailsAtStartup() {
        assertThatThrownBy(() -> build(props("", "", "", ""), "s3"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("S3_BUCKET");
    }

    @Test
    void anUnknownBackendFailsAtStartup() {
        assertThatThrownBy(() -> build(props("", "", "", ""), "gcs"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("'local' or 's3'");
    }
}
