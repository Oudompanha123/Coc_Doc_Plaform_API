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
 * treated as success (ADR-044). {@link #STATUS_CANCELLED} is a third way out
 * of {@code generating} specifically, kept distinct from {@code failed}
 * because a deliberate stop is a different fact from generation breaking on
 * its own (ADR-049).
 *
 * @param stagingPath where a Generate run's disposable working copy lives —
 *     null on the old placeholder path, set once generation starts, cleared
 *     once the run reaches a terminal state and its copy is deleted.
 * @param failureReason why a run failed, in the run's own words — null
 *     unless {@link #STATUS_FAILED}, never left for a bare status to imply.
 * @param confirmedPlanHash a SHA-256 of the confirmed-plan text this run was
 *     generated from — null on the placeholder path, which never builds
 *     one. Compared at Rebuild time (ADR-056) to tell "the plan is still
 *     exactly what this applied run was built from" from "something was
 *     edited and reconfirmed since"; a Replay run (see {@link
 *     #REPORT_MODE_REPLAY}) carries the same hash as the applied run it
 *     reproduces, since nothing about the plan changed to produce it.
 * @param runKind {@link #RUN_KIND_PLAN} (a confirmed Plan carried out — every
 *     run before ADR-060), {@link #RUN_KIND_ERROR_FIX} (an approved fix for
 *     a real Run Locally failure) or {@link #RUN_KIND_UI_CHANGE} (a
 *     natural-language UI change against the renewed project's own UI,
 *     never this admin app's) — ADR-060 Phase 2.
 * @param instruction the raw input this run was generated from, for
 *     error_fix/ui_change only — the real captured failure log, or the
 *     person's own free-text description. Null for plan, which rebuilds its
 *     own prompt from the confirmed Plan tables instead of storing one.
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
        String failureReason,
        String confirmedPlanHash,
        String runKind,
        String instruction) {

    /**
     * The shape every run had before ADR-060 Phase 2 — {@link #RUN_KIND_PLAN}
     * with no stored instruction, since a plan run rebuilds its own prompt
     * from the confirmed Plan tables instead. Kept so every existing call
     * site that builds a plan-kind run (production and test alike) does not
     * have to learn about the two new trailing fields.
     */
    public AxRenewalImplementationRun(
            UUID id,
            UUID projectId,
            String status,
            JsonNode beforeReport,
            JsonNode afterReport,
            Instant startedAt,
            UUID startedBy,
            Instant completedAt,
            String stagingPath,
            String failureReason,
            String confirmedPlanHash) {
        this(
                id,
                projectId,
                status,
                beforeReport,
                afterReport,
                startedAt,
                startedBy,
                completedAt,
                stagingPath,
                failureReason,
                confirmedPlanHash,
                RUN_KIND_PLAN,
                null);
    }

    /** {@code beforeReport.mode} for a run that reused a prior applied run's exact generated changes rather than asking the model again (ADR-056). */
    public static final String REPORT_MODE_REPLAY = "replay";

    public static final String STATUS_RUNNING = "running";
    public static final String STATUS_COMPLETED = "completed";
    public static final String STATUS_FAILED = "failed";
    public static final String STATUS_GENERATING = "generating";
    public static final String STATUS_AWAITING_REVIEW = "awaiting_review";
    public static final String STATUS_APPLYING = "applying";
    public static final String STATUS_APPLIED = "applied";
    public static final String STATUS_REJECTED = "rejected";
    public static final String STATUS_CANCELLED = "cancelled";
    /** A specific applied run undone on purpose (ADR-060 §revert) — distinct from every other terminal status, the same reasoning {@link #STATUS_CANCELLED}/{@link #STATUS_REJECTED} already establish. */
    public static final String STATUS_REVERTED = "reverted";

    public static final String RUN_KIND_PLAN = "plan";
    public static final String RUN_KIND_ERROR_FIX = "error_fix";
    public static final String RUN_KIND_UI_CHANGE = "ui_change";
}
