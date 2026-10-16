package com.coc.docs.store;

import com.coc.docs.config.AppProperties;
import com.coc.docs.storage.S3Clients;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Chooses where the Store keeps its files (ADR-115): {@code
 * coc.store.storage} (env {@code COC_STORE_STORAGE}) is {@code local} — the
 * default, for development and tests — or {@code s3}, which reuses the {@code
 * S3_*} bucket settings the avatars and content images already use. It is an
 * explicit switch, never inferred from {@code S3_*} being present: a
 * deployment that already has S3 for avatars must not have its Store move out
 * from under its existing local files on upgrade.
 *
 * <p>Choosing {@code s3} without a bucket is a startup failure, not a Store
 * that fails on the first upload.
 */
@Configuration
public class StoreFilesConfig {

    private static final Logger log = LoggerFactory.getLogger(StoreFilesConfig.class);

    @Bean
    public StoreFiles storeFiles(
            AppProperties properties,
            @Value("${coc.store.storage:local}") String backend,
            @Value("${coc.store.root:store-files}") String root,
            @Value("${coc.store.max-file-bytes:2147483648}") long maxFileBytes,
            @Value("${coc.store.download-url-ttl-seconds:300}") long downloadTtlSeconds) {
        return switch (backend.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "local" -> new StoreFileStorage(root, maxFileBytes);
            case "s3" -> {
                AppProperties.Storage storage = properties.storage();
                if (!storage.bucketConfigured()) {
                    throw new IllegalStateException(
                            "coc.store.storage=s3 needs S3_ENDPOINT and S3_BUCKET (and S3_REGION) to be set");
                }
                if (!storage.hasStaticKeys()) {
                    log.info("STORE: no S3 keys set; using the AWS default credential chain (EC2 instance role)");
                }
                yield new S3StoreFiles(
                        S3Clients.client(storage),
                        S3Clients.presigner(storage),
                        storage.bucket(),
                        maxFileBytes,
                        Duration.ofSeconds(downloadTtlSeconds));
            }
            default -> throw new IllegalStateException(
                    "coc.store.storage must be 'local' or 's3', not '" + backend + "'");
        };
    }
}
