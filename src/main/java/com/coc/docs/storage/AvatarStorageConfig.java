package com.coc.docs.storage;

import com.coc.docs.account.AvatarStorage;
import com.coc.docs.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.services.s3.S3Client;
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
    public AvatarStorage avatarStorage(AppProperties properties, FileInfoProperties files) {
        AppProperties.Storage storage = properties.storage();
        if (!storage.configured()) {
            // No object storage: keep photos on this backend's own disk when a
            // folder is configured for that (ADR-119). Object storage still
            // wins when both are set, so a deployment whose photos are already
            // in a bucket does not silently move away from them.
            if (files.configured()) {
                log.info("STORAGE: profile photos are kept on local disk under file.server-path");
                return new LocalAvatarStorage(files.serverPath());
            }
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

        // Built by S3Clients, shared with the Store (ADR-115): path-style, the
        // one addressing scheme every S3-compatible provider this system might
        // run against supports, which matters because the provider itself is
        // still undecided. The presigner agrees with the client on endpoint,
        // region, credentials and path-style, because a presigned URL is only
        // valid for the addressing scheme it was signed under, so a presigner
        // that disagreed would produce URLs that 403 (ADR-101).
        S3Client client = S3Clients.client(storage);
        S3Presigner presigner = S3Clients.presigner(storage);

        return new S3AvatarStorage(client, presigner, storage.bucket(), storage.endpoint());
    }
}
