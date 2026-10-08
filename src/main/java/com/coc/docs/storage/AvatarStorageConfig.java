package com.coc.docs.storage;

import com.coc.docs.account.AvatarStorage;
import com.coc.docs.config.AppProperties;
import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

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

    private static final Logger log = LoggerFactory.getLogger(AvatarStorageConfig.class);

    @Bean
    public AvatarStorage avatarStorage(AppProperties properties) {
        AppProperties.Storage storage = properties.storage();
        if (!storage.configured()) {
            return null;
        }

        // Said at startup, because the alternative is finding out from an
        // AccessDenied on the first upload -- which names neither the endpoint
        // nor the reason, and reads as a permissions problem rather than a
        // malformed URL.
        //
        // This client addresses objects path-style (host/bucket/key), so the
        // endpoint must be the REGIONAL host with no bucket in it. Given a
        // virtual-hosted endpoint like bucket.s3.region.amazonaws.com, the SDK
        // appends the bucket again and S3 reads "bucket/avatars/..." as the
        // object key -- which no sane IAM policy grants, hence AccessDenied.
        if (storage.endpoint().contains("://" + storage.bucket() + ".")) {
            log.warn(
                    "STORAGE: app.storage.endpoint contains the bucket name ({}). This client uses "
                            + "path-style addressing, so the bucket is appended to the endpoint and every "
                            + "upload will fail with AccessDenied. Use the regional endpoint instead, "
                            + "e.g. https://s3.{}.amazonaws.com",
                    storage.endpoint(),
                    storage.region());
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

        // Built from the same endpoint, region and credentials as the client
        // above, and with path-style for the same reason: a presigned URL is
        // only valid for the addressing scheme it was signed under, so a
        // presigner that disagreed with the client would produce URLs that
        // 403 rather than ones that merely look different (ADR-101).
        S3Presigner presigner = S3Presigner.builder()
                .endpointOverride(URI.create(storage.endpoint()))
                .region(Region.of(storage.region()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(storage.accessKeyId(), storage.secretAccessKey())))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(true)
                        .build())
                .build();

        return new S3AvatarStorage(client, presigner, storage.bucket(), storage.endpoint());
    }
}
