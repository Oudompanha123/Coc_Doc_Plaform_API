package com.coc.docs.store;

import java.time.Instant;
import java.util.UUID;

/**
 * One app on the Store page — a {@code store_app} row (V82). Its builds are
 * {@link StoreRelease}s.
 *
 * <p>{@code number} is {@code app_no}: what the app's page URL carries
 * ({@code /store-<number>}). {@code id} stays the key everything else uses.
 *
 * <p>// req: ADR-108
 */
public record StoreApp(
        UUID id,
        long number,
        String name,
        String description,
        StorePlatform platform,
        boolean isPublic,
        String logoPath,
        String logoType,
        Instant createdAt,
        Instant updatedAt) {

    public boolean hasLogo() {
        return logoPath != null;
    }
}
