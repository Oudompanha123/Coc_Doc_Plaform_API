package com.coc.docs.axrenewal;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

/**
 * A row of {@code ax_renewal_project} — one legacy-renewal planning workspace (ADR-037).
 *
 * <p>{@code revision} is the optimistic lock over the whole plan (ADR-067): a
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
        int revision,
        String sourceFilename) {

    /** A project with no uploaded source — {@code sourceFilename} is the stored archive's name under {@code file.server-path} (ADR-118). */
    public AxRenewalProject(
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
        this(
                id,
                name,
                description,
                status,
                manifestFilename,
                manifestContent,
                manifestAttachedAt,
                manifestAttachedBy,
                attachStats,
                createdBy,
                createdAt,
                updatedAt,
                revision,
                null);
    }

    public static final String STATUS_DRAFT = "draft";
    public static final String STATUS_ANALYZED = "analyzed";
    public static final String STATUS_PLANNING = "planning";
    public static final String STATUS_READY = "ready";
    public static final String STATUS_IMPLEMENTING = "implementing";
    public static final String STATUS_COMPLETED = "completed";
}
