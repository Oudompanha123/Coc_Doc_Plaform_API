package com.coc.docs.store;

import java.time.Instant;
import java.util.UUID;

/**
 * One uploaded install file of a {@link StoreApp} — a {@code store_build} row
 * (V85): one platform, one version, one environment. At most one live build
 * per (app, platform, version, environment).
 *
 * <p>{@code filePath} is relative to the store root and never leaves the
 * backend: responses carry the build id, and the download route resolves the
 * file from it.
 *
 * <p>// req: ADR-111
 */
public record StoreBuild(
        UUID id,
        UUID appId,
        StorePlatform platform,
        String version,
        StoreEnvironment environment,
        String notes,
        String fileName,
        String filePath,
        long sizeBytes,
        String sha256,
        Instant createdAt,
        /** When an admin removed it; null while live. A removed build's file is gone. */
        Instant removedAt,
        /** Display name of the account that uploaded it; null if that account was deleted. */
        String uploadedBy) {

    public boolean isLive() {
        return removedAt == null;
    }
}
