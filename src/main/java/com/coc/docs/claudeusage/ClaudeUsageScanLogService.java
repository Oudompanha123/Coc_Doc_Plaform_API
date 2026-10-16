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
 * <p><b>Running scans (ADR-107).</b> A reporter from 0.6.0 also announces a
 * scan as it starts ({@link #recordStart}); the row is {@code running} until
 * the result completes it. One still running {@link #RESULT_TIMEOUT} after the
 * server heard of it is read as {@code incomplete}: the server never got a
 * result, which is all that can honestly be said. Nothing is rewritten — a
 * result queued on the PC can still arrive and complete the row.
 *
 * <p><b>Two readers.</b> A super_admin reads every scan as stored
 * ({@link #list}, {@link #detail}); any member reads only their own
 * ({@link #listFor}, {@link #detailFor}), with error text cut to its first
 * line and file paths removed. The page's plain-language diagnosis needs the
 * message, not a stack trace or the PC's folder layout.
 *
 * <p>// req: FR-USAGE-002, ADR-099, ADR-107
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
    /** Stored while a scan has started and its result has not arrived (ADR-107). */
    public static final String RUNNING = "running";
    /** Read-time only: {@link #RUNNING} for longer than {@link #RESULT_TIMEOUT}. Never stored. */
    public static final String INCOMPLETE = "incomplete";
    private static final String SKIPPED = "skipped";
    private static final Set<String> STEP_STATUSES = Set.of(SUCCESS, WARNING, FAILED, SKIPPED);
    private static final Set<String> UPLOAD_STATUSES = Set.of("uploaded", "not-needed", "failed", "not-attempted");
    private static final Set<String> TRIGGERS = Set.of("scheduled", "manual");

    /** Outcome of {@link #record}, returned to the CLI as {@code action}. */
    public static final String RECORDED = "recorded";
    public static final String DUPLICATE = "duplicate";
    /** Outcome of {@link #recordStart}. */
    public static final String STARTED = "started";

    /**
     * How long a start may go without its result before the scan is read as
     * {@link #INCOMPLETE}. A scan takes seconds to a few minutes; half an hour
     * leaves room for a very large history without showing a dead scan as
     * "In progress" for the rest of the day.
     */
    static final Duration RESULT_TIMEOUT = Duration.ofMinutes(30);

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
    /**
     * A file path, for a member's own view: {@code C:\...}, {@code ~/...},
     * {@code \\server\...}, {@code /usr/lib/...} — but not the path inside a
     * URL, which the lookbehind leaves alone.
     */
    private static final Pattern PATH = Pattern.compile(
            "(?:(?<![A-Za-z])[A-Za-z]:[\\\\/]|~[\\\\/]|\\\\\\\\)[^\\s'\"]*|(?<![\\w.:/])/(?:[\\w.@-]+/)+[\\w.@-]*");
    private static final int MAX_MEMBER_TEXT = 200;

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

    /** A scan's start, as the reporter announced it (ADR-107). */
    public record Start(UUID scanId, String email, String trigger, String cliVersion, Instant startedAt) {}

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
                null, report.scanId(), memberId, email, null, null, trigger, clip(report.cliVersion(), MAX_VERSION),
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
        LocalDate[] range = range(from, to);
        List<ClaudeUsageScanLog> rows = logs.list(
                range[0].atStartOfDay(REPORT_ZONE).toInstant(),
                range[1].plusDays(1).atStartOfDay(REPORT_ZONE).toInstant(),
                MAX_LIST_ROWS + 1);
        return page(range, rows, false);
    }

    /** {@link #list}, for one member's own scans, as a member may read them. */
    @Transactional(readOnly = true)
    public Page listFor(String email, LocalDate from, LocalDate to) {
        LocalDate[] range = range(from, to);
        List<ClaudeUsageScanLog> rows = logs.listForEmail(
                normalizeEmail(email),
                range[0].atStartOfDay(REPORT_ZONE).toInstant(),
                range[1].plusDays(1).atStartOfDay(REPORT_ZONE).toInstant(),
                MAX_LIST_ROWS + 1);
        return page(range, rows, true);
    }

    /**
     * Stores a scan's start as a {@code running} row (ADR-107). The same
     * refusals as {@link #record} — a disabled member, a scan inside a
     * disabled period — so a start is never stored where its result would not
     * be. Idempotent on {@code scanId}; a start arriving after its result
     * changes nothing.
     */
    @Transactional
    public String recordStart(Start start) {
        String email = normalizeEmail(start.email());
        ClaudeUsageMember member = members.findByEmail(email).orElse(null);
        ClaudeUsageService.requireTrackingEnabled(member);
        if (start.scanId() == null || start.startedAt() == null) {
            throw ApiException.badRequest("scanId and startedAt are required");
        }
        if (member != null) {
            ClaudeUsageService.requireMomentOutsidePauses(start.startedAt(), pauses.listFor(member.id()));
        }
        Instant now = clock.instant();
        if (start.startedAt().isAfter(now.plus(MAX_CLOCK_SKEW))) {
            throw ApiException.badRequest("startedAt is in the future — check this PC's clock");
        }
        String trigger = TRIGGERS.contains(start.trigger()) ? start.trigger() : "manual";
        ClaudeUsageScanLog log = new ClaudeUsageScanLog(
                null, start.scanId(), member == null ? null : member.id(), email, null, null, trigger,
                clip(start.cliVersion(), MAX_VERSION), start.startedAt(), null, now, RUNNING, "not-attempted",
                null, null, null, null, null, null, null, List.of());
        return logs.insertRunning(log) ? STARTED : DUPLICATE;
    }

    private Page page(LocalDate[] range, List<ClaudeUsageScanLog> rows, boolean forMember) {
        boolean truncated = rows.size() > MAX_LIST_ROWS;
        Instant now = clock.instant();
        List<ClaudeUsageScanLog> shown = (truncated ? rows.subList(0, MAX_LIST_ROWS) : rows).stream()
                .map(l -> asRead(l, now, forMember))
                .toList();
        return new Page(range[0], range[1], shown, truncated);
    }

    /** {@code {start, end}}, inclusive and validated. */
    private LocalDate[] range(LocalDate from, LocalDate to) {
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
        return new LocalDate[] {start, end};
    }

    /** One record with its steps. */
    @Transactional(readOnly = true)
    public ClaudeUsageScanLog detail(UUID id) {
        ClaudeUsageScanLog log = logs.findById(id).orElseThrow(() -> ApiException.notFound("scan log"));
        return asRead(log.withSteps(readSteps(logs.stepsJson(id).orElse("[]"))), clock.instant(), false);
    }

    /**
     * {@link #detail}, for a member reading their own scan. Someone else's id
     * is "not found" — the same answer as an id that does not exist — so the
     * route cannot be used to learn which scans exist.
     */
    @Transactional(readOnly = true)
    public ClaudeUsageScanLog detailFor(String email, UUID id) {
        String own = normalizeEmail(email);
        ClaudeUsageScanLog log = logs.findById(id)
                .filter(l -> own.equals(l.email()))
                .orElseThrow(() -> ApiException.notFound("scan log"));
        return asRead(log.withSteps(readSteps(logs.stepsJson(id).orElse("[]"))), clock.instant(), true);
    }

    /** An overdue {@code running} reads as {@code incomplete}; a member's copy has its error text cut down. */
    private static ClaudeUsageScanLog asRead(ClaudeUsageScanLog log, Instant now, boolean forMember) {
        ClaudeUsageScanLog out = log;
        if (RUNNING.equals(out.status()) && out.receivedAt().plus(RESULT_TIMEOUT).isBefore(now)) {
            out = out.withStatus(INCOMPLETE);
        }
        if (forMember) {
            out = out.withErrorMessage(forMember(out.errorMessage()))
                    .withSteps(out.steps().stream()
                            .map(s -> new ClaudeUsageScanLog.Step(s.key(), s.status(), s.at(), forMember(s.detail())))
                            .toList());
        }
        return out;
    }

    /**
     * What a member sees of an error or step text: its first line (the CLI's
     * own one-line message; a stack trace, if an altered client sent one,
     * starts on the second), file paths replaced, clipped short. Keeps what
     * the page's diagnosis matches on — HTTP statuses and network error codes.
     */
    static String forMember(String text) {
        if (text == null) {
            return null;
        }
        String first = text.strip().lines().findFirst().orElse("");
        return clip(PATH.matcher(first).replaceAll("[path]"), MAX_MEMBER_TEXT);
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
