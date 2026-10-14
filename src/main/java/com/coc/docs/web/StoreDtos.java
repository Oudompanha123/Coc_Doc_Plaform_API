package com.coc.docs.web;

import com.coc.docs.store.StoreApp;
import com.coc.docs.store.StoreRelease;
import com.coc.docs.store.StoreService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Request and response shapes for the Store page (ADR-108). Kept out of
 * {@link ApiDtos}, which is already long, since nothing else shares them.
 *
 * <p>Neither response carries a path on disk: a build is fetched by its id
 * through {@code downloadUrl}, and the backend resolves the file from that.
 *
 * <p>// req: ADR-108
 */
public final class StoreDtos {

    private StoreDtos() {}

    public record AppRequest(
            @NotBlank String name,
            String description,
            String platform,
            @NotNull Boolean isPublic) {}

    public record ReleaseResponse(
            UUID id,
            String version,
            String environment,
            String notes,
            String fileName,
            long sizeBytes,
            String sha256,
            Instant createdAt,
            String downloadUrl) {

        static ReleaseResponse of(StoreRelease r) {
            return new ReleaseResponse(
                    r.id(),
                    r.version(),
                    r.environment().wire(),
                    r.notes(),
                    r.fileName(),
                    r.sizeBytes(),
                    r.sha256(),
                    r.createdAt(),
                    "/api/v1/store/releases/" + r.id() + "/download");
        }
    }

    public record AppResponse(
            UUID id,
            /** The app's number; its page is /store-<number>. */
            long number,
            String name,
            String description,
            String platform,
            boolean isPublic,
            /** Null when there is no logo. Carries the update time so a replaced logo is not served from cache. */
            String logoUrl,
            Instant createdAt,
            Instant updatedAt,
            List<ReleaseResponse> releases) {

        static AppResponse of(StoreService.CatalogEntry entry) {
            StoreApp a = entry.app();
            return new AppResponse(
                    a.id(),
                    a.number(),
                    a.name(),
                    a.description(),
                    a.platform().wire(),
                    a.isPublic(),
                    a.hasLogo() ? "/api/v1/store/apps/" + a.id() + "/logo?v=" + a.updatedAt().toEpochMilli() : null,
                    a.createdAt(),
                    a.updatedAt(),
                    entry.releases().stream().map(ReleaseResponse::of).toList());
        }
    }
}
