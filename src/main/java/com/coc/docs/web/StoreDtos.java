package com.coc.docs.web;

import com.coc.docs.store.StoreApp;
import com.coc.docs.store.StoreBuild;
import com.coc.docs.store.StoreService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Request and response shapes for the Store page (ADR-108, ADR-111). Kept out
 * of {@link ApiDtos}, which is already long, since nothing else shares them.
 *
 * <p>No response carries a path on disk: a build is fetched by its id through
 * {@code downloadUrl}, and the backend resolves the file from that.
 *
 * <p>// req: ADR-108, ADR-111
 */
public final class StoreDtos {

    private StoreDtos() {}

    /** Add App / Edit App info. {@code category} is "mobile" or "pc" (ADR-112). */
    public record AppRequest(
            @NotBlank String name, String description, @NotBlank String category, @NotNull Boolean isPublic) {}

    /**
     * The JSON header in front of an uploaded build's bytes (see
     * {@code AdminStoreController#uploadBuild}). {@code environments} holds
     * "production", "development", or both.
     */
    public record BuildUploadHeader(String version, List<String> environments, String notes, String fileName) {}

    public record BuildResponse(
            UUID id,
            String platform,
            String version,
            String environment,
            String notes,
            String fileName,
            long sizeBytes,
            String sha256,
            Instant createdAt,
            /** Null while live; set once an admin removed it. */
            Instant removedAt,
            /** "active" or "removed" — a removed build keeps its row for the history, but its file is gone. */
            String status,
            /** Who uploaded it; null if that account no longer exists. */
            String uploadedBy,
            /** Null for a removed build — there is no file behind it any more. */
            String downloadUrl) {

        static BuildResponse of(StoreBuild b) {
            return new BuildResponse(
                    b.id(),
                    b.platform().wire(),
                    b.version(),
                    b.environment().wire(),
                    b.notes(),
                    b.fileName(),
                    b.sizeBytes(),
                    b.sha256(),
                    b.createdAt(),
                    b.removedAt(),
                    b.isLive() ? "active" : "removed",
                    b.uploadedBy(),
                    b.isLive() ? "/api/v1/store/builds/" + b.id() + "/download" : null);
        }
    }

    /** Pin an app to the Store banner, or {@code null} for the latest automatically (ADR-117). */
    public record FeaturedRequest(UUID appId) {}

    /** The pinned app, or {@code null} when the banner shows the latest upload or update. */
    public record FeaturedResponse(UUID appId) {}

    /** Where to fetch a build from: a presigned S3 URL, or this API's own download route. */
    public record DownloadLink(String url, boolean presigned, Instant expiresAt) {}

    public record AppResponse(
            UUID id,
            /** The app's number; its page is /store-<number>. */
            long number,
            String name,
            String description,
            /** "mobile" or "pc". */
            String category,
            boolean isPublic,
            /** Null when there is no logo. Carries the update time so a replaced logo is not served from cache. */
            String logoUrl,
            /** The wide picture for the Store banner (ADR-117); null when there is none. Cache-busted like logoUrl. */
            String bannerUrl,
            Instant createdAt,
            Instant updatedAt,
            /** Every build, removed ones included, newest first. */
            List<BuildResponse> builds) {

        static AppResponse of(StoreService.CatalogEntry entry) {
            StoreApp a = entry.app();
            return new AppResponse(
                    a.id(),
                    a.number(),
                    a.name(),
                    a.description(),
                    a.category().wire(),
                    a.isPublic(),
                    a.hasLogo() ? "/api/v1/store/apps/" + a.id() + "/logo?v=" + a.updatedAt().toEpochMilli() : null,
                    a.hasBanner() ? "/api/v1/store/apps/" + a.id() + "/banner?v=" + a.updatedAt().toEpochMilli() : null,
                    a.createdAt(),
                    a.updatedAt(),
                    entry.builds().stream().map(BuildResponse::of).toList());
        }
    }
}
