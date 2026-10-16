package com.coc.docs.store;

import java.time.Instant;
import java.util.UUID;

/**
 * One app on the Store page — a {@code store_app} row (V82, V85): its name,
 * logo, description, Mobile/PC category and visibility. An app is not tied to a platform: its
 * {@link StoreBuild}s each carry their own (ADR-111).
 *
 * <p>{@code number} is {@code app_no}: what the app's page URL carries
 * ({@code /store-<number>}). {@code id} stays the key everything else uses.
 *
 * <p>// req: ADR-108, ADR-111, ADR-112
 */
public record StoreApp(
        UUID id,
        long number,
        String name,
        String description,
        StoreCategory category,
        boolean isPublic,
        String logoPath,
        String logoType,
        Instant createdAt,
        Instant updatedAt) {

    public boolean hasLogo() {
        return logoPath != null;
    }
}
