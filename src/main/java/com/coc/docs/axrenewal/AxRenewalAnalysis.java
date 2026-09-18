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
 */
public record AxRenewalAnalysis(
        UUID projectId,
        JsonNode stackDetected,
        JsonNode findings,
        JsonNode pagesDetected,
        boolean modelConnected,
        Instant analyzedAt,
        UUID analyzedBy) {}
