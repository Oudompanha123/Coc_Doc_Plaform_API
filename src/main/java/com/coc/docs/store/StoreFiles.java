package com.coc.docs.store;

import java.io.InputStream;
import java.net.URI;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import org.springframework.core.io.Resource;

/**
 * Where the Store's files live (ADR-108, ADR-115): the backend's local disk
 * ({@link StoreFileStorage}, for development and tests) or S3
 * ({@link S3StoreFiles}, for a deployment). {@code StoreService} talks only to
 * this, so the choice is a setting, not a code path.
 *
 * <p>Both address a file by the same relative key —
 * {@code {platform}/{version}/{environment}/{appId}/{fileName}} for a build,
 * {@code logos/{appId}.{ext}} for a logo — which is what the database stores,
 * so switching backends never rewrites a row.
 *
 * <p>// req: ADR-108, ADR-111, ADR-115
 */
public interface StoreFiles {

    /** A logo as written: where and how big. */
    record StoredFile(String relativePath, long sizeBytes) {}

    /** A build as written: its key per environment, its size and its SHA-256. */
    record StoredBuild(Map<StoreEnvironment, String> paths, long sizeBytes, String sha256) {}

    /** How a build reaches the client: streamed by the backend, or fetched straight from S3. */
    sealed interface Delivery {
        /** A file on the backend's disk, streamed by the backend. */
        record LocalFile(Path path) implements Delivery {}

        /** A short-lived signed URL; the bytes never pass through the backend. */
        record Presigned(URI url, Instant expiresAt) implements Delivery {}
    }

    long maxFileBytes();

    /**
     * Streams a build to its key for each of {@code environments}. {@code
     * fileName} must already be {@link StoreFileRules#safeFileName safe}.
     *
     * <p>An existing object at a target key is never replaced blindly — it may
     * be a build someone has already downloaded. {@code existingIsStale} is
     * asked only when one is there, and answers whether it is debris (no live
     * build row points at it) that may be overwritten; otherwise the upload is
     * refused with 409 and nothing is written.
     */
    StoredBuild storeBuild(
            StorePlatform platform,
            String version,
            List<StoreEnvironment> environments,
            UUID appId,
            String fileName,
            InputStream body,
            Predicate<StoreEnvironment> existingIsStale);

    StoredFile storeLogo(UUID appId, String contentType, InputStream body);

    /** Removes a stored file. A file that is already gone is not an error. */
    void delete(String relativePath);

    /**
     * How to hand a build to a client. 404 when the file is not there, so a
     * live row whose file went missing is "not found" rather than a broken
     * download.
     */
    Delivery deliver(String relativePath, String fileName, String contentType);

    /** A logo's bytes (at most 2 MB). 404 when the file is not there. */
    Resource openLogo(String relativePath);
}
