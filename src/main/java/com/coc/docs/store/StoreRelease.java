package com.coc.docs.store;

import java.time.Instant;
import java.util.UUID;

/**
 * One downloadable build of a {@link StoreApp} — a {@code store_app_release}
 * row (V82). At most one per (app, version, environment).
 *
 * <p>{@code filePath} is relative to the store root and never leaves the
 * backend: responses carry the release id, and the download route resolves
 * the file from it.
 *
 * <p>// req: ADR-108
 */
public record StoreRelease(
        UUID id,
        UUID appId,
        String version,
        StoreEnvironment environment,
        String notes,
        String fileName,
        String filePath,
        long sizeBytes,
        String sha256,
        Instant createdAt) {}
