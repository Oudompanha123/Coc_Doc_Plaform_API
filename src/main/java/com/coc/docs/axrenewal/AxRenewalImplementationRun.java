package com.coc.docs.axrenewal;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

/**
 * A row of {@code ax_renewal_implementation_run} — one implementation
 * attempt.
 *
 * <p>Two paths, chosen by {@link AxRenewalCodeGenerator#canGenerate()} at
 * start time. Without a local model and a filesystem destination, a run goes
 * straight from {@link #STATUS_RUNNING} to {@link #STATUS_COMPLETED} with a
 * before/after report and nothing else — the original honest placeholder,
 * unchanged. With both, a run instead moves {@link #STATUS_GENERATING} →
 * {@link #STATUS_AWAITING_REVIEW} → ({@link #STATUS_APPLYING} →
 * {@link #STATUS_APPLIED}) or {@link #STATUS_REJECTED}, or
 * {@link #STATUS_FAILED} at any point along the way — never silently
 * treated as success (ADR-044).
 *
 * @param stagingPath where a Generate run's disposable working copy lives —
 *     null on the old placeholder path, set once generation starts, cleared
 *     once the run reaches a terminal state and its copy is deleted.
 * @param failureReason why a run failed, in the run's own words — null
 *     unless {@link #STATUS_FAILED}, never left for a bare status to imply.
 */
public record AxRenewalImplementationRun(
        UUID id,
        UUID projectId,
        String status,
        JsonNode beforeReport,
        JsonNode afterReport,
        Instant startedAt,
        UUID startedBy,
        Instant completedAt,
        String stagingPath,
        String failureReason) {

    public static final String STATUS_RUNNING = "running";
    public static final String STATUS_COMPLETED = "completed";
    public static final String STATUS_FAILED = "failed";
    public static final String STATUS_GENERATING = "generating";
    public static final String STATUS_AWAITING_REVIEW = "awaiting_review";
    public static final String STATUS_APPLYING = "applying";
    public static final String STATUS_APPLIED = "applied";
    public static final String STATUS_REJECTED = "rejected";
}
