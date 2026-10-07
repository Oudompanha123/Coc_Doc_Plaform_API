package com.coc.docs.claudeusage;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * One {@code claude-team-usage scan} as its reporter recorded it (V66, ADR-099).
 *
 * @param memberId     the roster row the email matched when the log arrived, or null
 * @param displayName  the roster's display name, or null when the email has no roster row
 * @param startedAt    the PC's own clock — it describes the scan; {@code receivedAt} is server time
 * @param status       {@code success} / {@code warning} / {@code failed}, derived from {@code steps}
 * @param uploadStatus {@code uploaded} / {@code not-needed} / {@code failed} / {@code not-attempted}
 * @param newTokens    growth over what that PC had already reported; 0 for "no new usage"; null when not reached
 * @param steps        in {@link ClaudeUsageScanLogService#STEPS} order; empty in a list read, which leaves them out
 *
 * <p>// req: FR-USAGE-002, ADR-099
 */
public record ClaudeUsageScanLog(
        UUID id,
        UUID clientScanId,
        UUID memberId,
        String email,
        String displayName,
        String trigger,
        String cliVersion,
        Instant startedAt,
        Instant finishedAt,
        Instant receivedAt,
        String status,
        String uploadStatus,
        Integer transcriptFiles,
        Integer responses,
        Integer duplicates,
        Long newTokens,
        Integer daysUploaded,
        String errorStep,
        String errorMessage,
        List<Step> steps) {

    private static final DateTimeFormatter REF_TIME =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT).withZone(ZoneId.of("Asia/Phnom_Penh"));

    /** One step of the scan. {@code at} is null for a step that never ran ({@code skipped}). */
    public record Step(String key, String status, Instant at, String detail) {}

    /**
     * The human-readable scan ID, e.g. {@code SCAN-20261007-110001-3F2A}: the
     * start time in the team's reporting zone plus four characters of the
     * client's own id, so two PCs scanning in the same second still differ.
     */
    public String scanRef() {
        return "SCAN-" + REF_TIME.format(startedAt) + "-"
                + clientScanId.toString().substring(0, 4).toUpperCase(Locale.ROOT);
    }

    public long durationMs() {
        return Math.max(0, finishedAt.toEpochMilli() - startedAt.toEpochMilli());
    }

    public ClaudeUsageScanLog withSteps(List<Step> newSteps) {
        return new ClaudeUsageScanLog(id, clientScanId, memberId, email, displayName, trigger, cliVersion, startedAt,
                finishedAt, receivedAt, status, uploadStatus, transcriptFiles, responses, duplicates, newTokens,
                daysUploaded, errorStep, errorMessage, newSteps);
    }
}
