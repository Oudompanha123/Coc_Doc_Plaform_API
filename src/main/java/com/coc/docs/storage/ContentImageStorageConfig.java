package com.coc.docs.storage;

import com.coc.docs.config.AppProperties;
import java.net.URI;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

/**
 * Builds the S3-compatible client for docs-content image uploads, only when
 * {@code app.storage} is actually configured — same optional-bean shape as
 * {@link AvatarStorageConfig}, and the same reasoning: a deployment with no
 * object-storage credentials yet (CONST-TECH-08, ADR-012 §2 — hosting still
 * {@code [DEFERRED]}) must boot and serve every other route regardless.
 */
@Configuration
public class ContentImageStorageConfig {

    @Bean
    public ContentImageStorage contentImageStorage(AppProperties properties) {
        AppProperties.Storage storage = properties.storage();
        if (!storage.configured()) {
            return null;
        }

        S3Client client = S3Client.builder()
                .endpointOverride(URI.create(storage.endpoint()))
                .region(Region.of(storage.region()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(storage.accessKeyId(), storage.secretAccessKey())))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build();

        return new S3ContentImageStorage(client, storage.bucket(), storage.endpoint());
    }
}
