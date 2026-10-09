package com.coc.docs.claudeusage;

import com.coc.docs.web.ApiException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The scan log behind the admin "View Logs" page (V66, ADR-099): one record
 * per {@code claude-team-usage scan}, written by the reporter at
 * {@code POST /api/ingest/scan-log} and read by admins.
 *
 * <p>Kept apart from {@link ClaudeUsageService} on purpose. That service owns
 * the usage numbers; this one only describes scans, and never changes a token
 * count, {@code last_scan_at}, or anything the dashboard shows.
 *
 * <p><b>Status is decided here, not by the CLI:</b> {@code failed} when a
 * step failed, else {@code warning} when one warned, else {@code success}. "No
 * new usage" is a successful step, so a scan that found nothing new is a
 * successful scan.
 *
 * <p>// req: FR-USAGE-002, ADR-099
 */
@Service
public class ClaudeUsageScanLogService {

    /** The fixed step catalogue, in timeline order — the CLI's {@code scan-log.js} STEPS. */
    public static final List<String> STEPS = List.of(
            "start", "find-transcripts", "parse-usage", "deduplicate", "calculate-usage", "check-new-usage", "upload",
            "complete");

    public static final String SUCCESS = "success";
    public static final String WARNING = "warning";
    public static final String FAILED = "failed";
    private static final String SKIPPED = "skipped";
    private static final Set<String> STEP_STATUSES = Set.of(SUCCESS, WARNING, FAILED, SKIPPED);
    private static final Set<String> UPLOAD_STATUSES = Set.of("uploaded", "not-needed", "failed", "not-attempted");
    private static final Set<String> TRIGGERS = Set.of("scheduled", "manual");

    /** Outcome of {@link #record}, returned to the CLI as {@code action}. */
    public static final String RECORDED = "recorded";
    public static final String DUPLICATE = "duplicate";

    static final Duration RETENTION = Duration.ofDays(90);
    static final int MAX_LIST_ROWS = 2000;
    static final int MAX_RANGE_DAYS = 92;
    private static final int MAX_DETAIL = 300;
    private static final int MAX_ERROR = 500;
    private static final int MAX_VERSION = 40;
    private static final Duration MAX_SCAN_LENGTH = Duration.ofHours(6);
    private static final Duration MAX_CLOCK_SKEW = Duration.ofDays(1);
    private static final ZoneId REPORT_ZONE = ZoneId.of("Asia/Phnom_Penh");

    /** The CLI already redacts; this is the second line, in case an older or altered client did not. */
    private static final Pattern TOKEN = Pattern.compile("coc_[A-Za-z0-9_-]+");
    private static final Pattern BEARER = Pattern.compile("(?i)bearer\\s+\\S+");

    private final ClaudeUsageScanLogRepository logs;
    private final ClaudeUsageMemberRepository members;
    private final ObjectMapper json;
    private final Clock clock;
    private final ClaudeUsageTrackingPauseRepository pauses;

    public ClaudeUsageScanLogService(
            ClaudeUsageScanLogRepository logs,
            ClaudeUsageMemberRepository members,
            ObjectMapper json,
            Clock clock,
            ClaudeUsageTrackingPauseRepository pauses) {
        this.logs = logs;
        this.members = members;
        this.json = json;
        this.clock = clock;
        this.pauses = pauses;
    }

    /** What the reporter sent, before validation. Counts are null when the scan never reached them. */
    public record Report(
            UUID scanId,
            String email,
            String trigger,
            String cliVersion,
            Instant startedAt,
            Instant finishedAt,
            Integer transcriptFiles,
            Integer responses,
            Integer duplicates,
            Long newTokens,
            Integer daysUploaded,
            String uploadStatus,
            List<ClaudeUsageScanLog.Step> steps,
            String errorStep,
            String errorMessage) {}

    /** A date range of logs. {@code truncated}: more than {@link #MAX_LIST_ROWS} started in it. */
    public record Page(LocalDate from, LocalDate to, List<ClaudeUsageScanLog> logs, boolean truncated) {}

    /**
     * Stores one scan's record. Idempotent on {@code scanId}: the CLI resends a
     * record it could not deliver earlier, and the second copy is ignored.
     */
    @Transactional
    public String record(Report report) {
        String email = normalizeEmail(report.email());
        // A disabled member's scan log is refused too (ADR-105) -- checked
        // first, so it is refused whatever the record holds.
        ClaudeUsageMember member = members.findByEmail(email).orElse(null);
        ClaudeUsageService.requireTrackingEnabled(member);
        // Enabled again, but this scan ran while disabled: a record the CLI
        // queued then, refused for good.
        if (member != null && report.startedAt() != null) {
            ClaudeUsageService.requireMomentOutsidePauses(report.startedAt(), pauses.listFor(member.id()));
        }
        if (report.scanId() == null || report.startedAt() == null || report.finishedAt() == null) {
            throw ApiException.badRequest("scanId, startedAt and finishedAt are required");
        }
        Instant now = clock.instant();
        if (report.finishedAt().isBefore(report.startedAt())) {
            throw ApiException.badRequest("finishedAt must not be before startedAt");
        }
        if (Duration.between(report.startedAt(), report.finishedAt()).compareTo(MAX_SCAN_LENGTH) > 0) {
            throw ApiException.badRequest("a scan cannot last longer than " + MAX_SCAN_LENGTH.toHours() + " hours");
        }
        if (report.finishedAt().isAfter(now.plus(MAX_CLOCK_SKEW))) {
            throw ApiException.badRequest("finishedAt is in the future — check this PC's clock");
        }
        String uploadStatus = report.uploadStatus() == null ? "not-attempted" : report.uploadStatus();
        if (!UPLOAD_STATUSES.contains(uploadStatus)) {
            throw ApiException.badRequest("uploadStatus must be one of " + UPLOAD_STATUSES);
        }
        String errorStep = report.errorStep();
        if (errorStep != null && !STEPS.contains(errorStep)) {
            throw ApiException.badRequest("errorStep must be one of " + STEPS);
        }
        requireNonNegative(report.transcriptFiles(), report.responses(), report.duplicates(), report.daysUploaded());
        if (report.newTokens() != null && report.newTokens() < 0) {
            throw ApiException.badRequest("counts must not be negative");
        }

        List<ClaudeUsageScanLog.Step> steps = normalizeSteps(report.steps());
        String trigger = TRIGGERS.contains(report.trigger()) ? report.trigger() : "manual";
        UUID memberId = member == null ? null : member.id();

        ClaudeUsageScanLog log = new ClaudeUsageScanLog(
                null, report.scanId(), memberId, email, null, trigger, clip(report.cliVersion(), MAX_VERSION),
                report.startedAt(), report.finishedAt(), now, deriveStatus(steps, errorStep), uploadStatus,
                report.transcriptFiles(), report.responses(), report.duplicates(), report.newTokens(),
                report.daysUploaded(), errorStep, clip(redact(report.errorMessage()), MAX_ERROR), steps);

        boolean inserted = logs.insert(log, writeSteps(steps));
        logs.deleteStartedBefore(now.minus(RETENTION));
        return inserted ? RECORDED : DUPLICATE;
    }

    /**
     * Scans that started on {@code from}..{@code to} (inclusive calendar
     * dates in the team's reporting zone), newest first. Both or neither:
     * neither is the last 7 days, today included.
     */
    @Transactional(readOnly = true)
    public Page list(LocalDate from, LocalDate to) {
        if ((from == null) != (to == null)) {
            throw ApiException.badRequest("from and to must be given together");
        }
        LocalDate end = to != null ? to : LocalDate.now(clock.withZone(REPORT_ZONE));
        LocalDate start = from != null ? from : end.minusDays(6);
        if (start.isAfter(end)) {
            throw ApiException.badRequest("from must not be after to");
        }
        if (ChronoUnit.DAYS.between(start, end) + 1 > MAX_RANGE_DAYS) {
            throw ApiException.badRequest("a range can cover at most " + MAX_RANGE_DAYS + " days");
        }
        List<ClaudeUsageScanLog> rows = logs.list(
                start.atStartOfDay(REPORT_ZONE).toInstant(),
                end.plusDays(1).atStartOfDay(REPORT_ZONE).toInstant(),
                MAX_LIST_ROWS + 1);
        boolean truncated = rows.size() > MAX_LIST_ROWS;
        return new Page(start, end, truncated ? rows.subList(0, MAX_LIST_ROWS) : rows, truncated);
    }

    /** One record with its steps. */
    @Transactional(readOnly = true)
    public ClaudeUsageScanLog detail(UUID id) {
        ClaudeUsageScanLog log = logs.findById(id).orElseThrow(() -> ApiException.notFound("scan log"));
        return log.withSteps(readSteps(logs.stepsJson(id).orElse("[]")));
    }

    /** Failed beats warning beats success. An {@code errorStep} is a failure even if its step was not sent. */
    static String deriveStatus(List<ClaudeUsageScanLog.Step> steps, String errorStep) {
        if (errorStep != null || steps.stream().anyMatch(s -> FAILED.equals(s.status()))) {
            return FAILED;
        }
        return steps.stream().anyMatch(s -> WARNING.equals(s.status())) ? WARNING : SUCCESS;
    }

    /** Catalogue order, one entry per step; a step the CLI did not send is {@code skipped}. */
    private static List<ClaudeUsageScanLog.Step> normalizeSteps(List<ClaudeUsageScanLog.Step> raw) {
        List<ClaudeUsageScanLog.Step> given = raw == null ? List.of() : raw;
        if (given.size() > STEPS.size()) {
            throw ApiException.badRequest("at most " + STEPS.size() + " steps");
        }
        Map<String, ClaudeUsageScanLog.Step> byKey = new LinkedHashMap<>();
        Set<String> seen = new HashSet<>();
        for (ClaudeUsageScanLog.Step step : given) {
            if (step == null || !STEPS.contains(step.key())) {
                throw ApiException.badRequest("each step key must be one of " + STEPS);
            }
            if (!seen.add(step.key())) {
                throw ApiException.badRequest("step " + step.key() + " is listed twice");
            }
            if (!STEP_STATUSES.contains(step.status())) {
                throw ApiException.badRequest("each step status must be one of " + STEP_STATUSES);
            }
            byKey.put(step.key(), new ClaudeUsageScanLog.Step(
                    step.key(), step.status(), step.at(), clip(redact(step.detail()), MAX_DETAIL)));
        }
        List<ClaudeUsageScanLog.Step> out = new ArrayList<>(STEPS.size());
        for (String key : STEPS) {
            out.add(byKey.getOrDefault(key, new ClaudeUsageScanLog.Step(key, SKIPPED, null, "")));
        }
        return out;
    }

    private String writeSteps(List<ClaudeUsageScanLog.Step> steps) {
        try {
            return json.writeValueAsString(steps);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("could not serialize scan steps", e);
        }
    }

    private List<ClaudeUsageScanLog.Step> readSteps(String stored) {
        try {
            return json.readValue(stored, new TypeReference<List<ClaudeUsageScanLog.Step>>() {});
        } catch (JsonProcessingException e) {
            // A row this service wrote is always readable; an unreadable one still shows its summary.
            return List.of();
        }
    }

    static String redact(String text) {
        if (text == null) {
            return null;
        }
        return BEARER.matcher(TOKEN.matcher(text).replaceAll("coc_***")).replaceAll("Bearer ***");
    }

    private static String clip(String text, int max) {
        if (text == null) {
            return null;
        }
        String trimmed = text.strip();
        return trimmed.length() > max ? trimmed.substring(0, max - 1) + "…" : trimmed;
    }

    private static void requireNonNegative(Integer... counts) {
        for (Integer count : counts) {
            if (count != null && count < 0) {
                throw ApiException.badRequest("counts must not be negative");
            }
        }
    }

    private static String normalizeEmail(String raw) {
        if (raw == null) {
            throw ApiException.badRequest("email is required");
        }
        String email = raw.trim().toLowerCase(Locale.ROOT);
        if (email.isEmpty() || !email.contains("@") || email.length() > 254) {
            throw ApiException.badRequest("email must be a valid email address");
        }
        return email;
    }
}
