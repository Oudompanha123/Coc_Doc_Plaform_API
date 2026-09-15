package com.coc.docs.storage;

import com.coc.docs.account.AvatarStorage;
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
 * Builds the S3-compatible client for manual avatar uploads (ADR-026), and
 * only when {@code app.storage} is actually configured.
 *
 * <p>Returns {@code null} rather than being {@code @ConditionalOn}-guarded:
 * a factory method returning {@code null} registers no bean at all, which is
 * exactly what {@code SessionService}'s {@code ObjectProvider<AvatarStorage>}
 * needs to see on a deployment that has not chosen an object-storage provider
 * yet (CONST-TECH-08, ADR-012 §2 — still {@code [DEFERRED]}). The application
 * must boot and serve every other route regardless — the same shape as
 * {@code KeycloakAuthConfig} for a deployment with no Keycloak realm.
 */
@Configuration
public class AvatarStorageConfig {

    @Bean
    public AvatarStorage avatarStorage(AppProperties properties) {
        AppProperties.Storage storage = properties.storage();
        if (!storage.configured()) {
            return null;
        }

        S3Client client = S3Client.builder()
                .endpointOverride(URI.create(storage.endpoint()))
                .region(Region.of(storage.region()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(storage.accessKeyId(), storage.secretAccessKey())))
                // Path-style (host/bucket/key), not virtual-hosted (bucket.host/key):
                // the one addressing scheme every S3-compatible provider this
                // system might eventually run against — MinIO included — is
                // guaranteed to support, which matters precisely because the
                // provider itself is still undecided.
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build();

        return new S3AvatarStorage(client, storage.bucket(), storage.endpoint());
    }
}
