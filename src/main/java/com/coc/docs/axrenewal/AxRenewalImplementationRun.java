package com.coc.docs.axrenewal;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

/**
 * A row of {@code ax_renewal_implementation_run} — one implementation
 * attempt (ADR-037). Phase 1 never runs a real build; this records a
 * simulated run's before/after report, matching the prototype's own
 * behaviour but backed by a real row.
 */
public record AxRenewalImplementationRun(
        UUID id,
        UUID projectId,
        String status,
        JsonNode beforeReport,
        JsonNode afterReport,
        Instant startedAt,
        UUID startedBy,
        Instant completedAt) {

    public static final String STATUS_RUNNING = "running";
    public static final String STATUS_COMPLETED = "completed";
    public static final String STATUS_FAILED = "failed";
}
