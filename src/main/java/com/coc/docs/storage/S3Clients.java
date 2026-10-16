package com.coc.docs.storage;

import com.coc.docs.config.AppProperties;
import java.net.URI;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * The one place an S3 client and presigner are built from {@code app.storage}
 * (the {@code S3_*} settings), so avatars, content images and the Store all use
 * the same endpoint, region, credentials and addressing (CONST-TECH-08).
 *
 * <p>Path-style addressing, because it is the one scheme every S3-compatible
 * provider supports, and a presigned URL is only valid for the scheme it was
 * signed under — so the client and the presigner must agree (ADR-101).
 *
 * <p>Credentials: the static keys when both are set; otherwise the AWS default
 * chain, which on EC2 means the instance's IAM role (ADR-115).
 */
public final class S3Clients {

    private S3Clients() {}

    public static AwsCredentialsProvider credentials(AppProperties.Storage storage) {
        return storage.hasStaticKeys()
                ? StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(storage.accessKeyId(), storage.secretAccessKey()))
                : DefaultCredentialsProvider.builder().build();
    }

    public static S3Client client(AppProperties.Storage storage) {
        return S3Client.builder()
                .endpointOverride(URI.create(storage.endpoint()))
                .region(Region.of(storage.region()))
                .credentialsProvider(credentials(storage))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build();
    }

    public static S3Presigner presigner(AppProperties.Storage storage) {
        return S3Presigner.builder()
                .endpointOverride(URI.create(storage.endpoint()))
                .region(Region.of(storage.region()))
                .credentialsProvider(credentials(storage))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build();
    }
}
