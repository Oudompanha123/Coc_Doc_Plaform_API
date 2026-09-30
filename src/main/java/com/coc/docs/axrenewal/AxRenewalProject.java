package com.coc.docs.axrenewal;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

/**
 * A row of {@code ax_renewal_project} — one legacy-renewal planning workspace (ADR-037).
 *
 * <p>{@code revision} is the optimistic lock over the whole plan (ADR-061): a
 * plan edit sends back the value it loaded, and a stale one is refused.
 */
public record AxRenewalProject(
        UUID id,
        String name,
        String description,
        String status,
        String manifestFilename,
        String manifestContent,
        Instant manifestAttachedAt,
        UUID manifestAttachedBy,
        JsonNode attachStats,
        UUID createdBy,
        Instant createdAt,
        Instant updatedAt,
        int revision) {

    public static final String STATUS_DRAFT = "draft";
    public static final String STATUS_ANALYZED = "analyzed";
    public static final String STATUS_PLANNING = "planning";
    public static final String STATUS_READY = "ready";
    public static final String STATUS_IMPLEMENTING = "implementing";
    public static final String STATUS_COMPLETED = "completed";
}
