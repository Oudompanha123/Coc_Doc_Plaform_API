package com.coc.docs.axrenewal;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

/**
 * A row of {@code ax_renewal_analysis} — the single analysis result for one
 * project.
 *
 * <p>{@code modelConnected} is always {@code false} in Phase 1: no
 * third-party AI call exists yet (ADR-037). Kept as a real column rather than
 * inferred, so a later phase's genuine analyses are structurally
 * distinguishable from today's deterministic stub.
 *
 * @param indexed lines/routes/templates/dependencies/testCoveragePercent —
 *     {@code {}} (never estimated) unless a connected local model supplied
 *     one (ADR-043). File count and language shares live on {@code
 *     ax_renewal_project.attach_stats} instead, since the browser already
 *     measures those for real without any model at all.
 * @param runningOnToday what the project visibly runs on today, each entry
 *     an observation with an optional caution note — {@code []} on the
 *     keyword-stub path, the same "never fabricate" rule as {@code indexed}.
 * @param fileLayout directories the model actually saw under {@code
 *     source-path} — {@code []} on the keyword-stub path or a manifest-only
 *     run. The frontend prefers the browser's own attach-time scan
 *     ({@code AxRenewalProject.attachStats}) when that exists, since it is a
 *     full scan rather than whatever subset the model looked at; this is the
 *     fallback for a project attached before that scan existed.
 * @param moduleCoupling which modules import which — {@code {}} on the
 *     keyword-stub path or a manifest-only run, since tracing an import
 *     graph needs the actual source. Backs the Architecture panel's "Module
 *     coupling" diagram.
 */
public record AxRenewalAnalysis(
        UUID projectId,
        JsonNode stackDetected,
        JsonNode findings,
        JsonNode pagesDetected,
        JsonNode indexed,
        JsonNode runningOnToday,
        JsonNode fileLayout,
        JsonNode moduleCoupling,
        boolean modelConnected,
        Instant analyzedAt,
        UUID analyzedBy) {}
