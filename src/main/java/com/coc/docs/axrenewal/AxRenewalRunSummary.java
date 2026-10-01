package com.coc.docs.axrenewal;

import java.time.Instant;
import java.util.UUID;

/**
 * One past implementation run, as the Build → Implementation history lists it
 * (ADR-078). Read-only, and deliberately its own record rather than a wider
 * {@link AxRenewalImplementationRun}: a history row needs who started it (a
 * display name, never an email — NFR-SEC-PII) and how many files it changed,
 * and none of the reports or the staging path a run carries.
 *
 * @param instruction the request a UI change was made from, or the captured
 *     failure an error fix was made for — null for a plan build, which builds
 *     from the confirmed Plan instead (ADR-062).
 * @param changedFiles the run's proposed file changes — zero for a run that
 *     failed, was cancelled, or never produced any.
 */
public record AxRenewalRunSummary(
        UUID id,
        String runKind,
        String status,
        Instant startedAt,
        UUID startedBy,
        String startedByName,
        Instant completedAt,
        String failureReason,
        String instruction,
        int changedFiles) {}
