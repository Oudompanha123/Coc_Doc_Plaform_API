package com.coc.docs.claudeusage;

import com.coc.docs.audit.AuditAction;
import com.coc.docs.audit.AuditRepository;
import com.coc.docs.auth.CurrentUser;
import com.coc.docs.web.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
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
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Roster management, ingestion, and the dashboard rollup for Claude Usage
 * (ADR-035). Cost is priced per model at real Anthropic API rates
 * ({@link ClaudeUsagePricing}), not a single blended rate (ADR-036, revision
 * of ADR-035 decision 4).
 *
 * <p>// req: FR-USAGE-001, FR-USAGE-002, FR-USAGE-003, NFR-SEC-PII, ADR-035, ADR-036, ADR-058
 */
@Service
public class ClaudeUsageService {

    /**
     * Weekly-equivalent soft budget every member uses unless they have their
     * own override, in tokens. An estimate the team sets for itself, never an
     * official Anthropic limit — matches the figure the dashboard's mock
     * already used before this was real data.
     */
    public static final long DEFAULT_SOFT_BUDGET_TOKENS = 1_200_000L;

    /**
     * Soft-budget status thresholds, matching the reference tool's own
     * amber/red split rather than a single cutoff (ADR-035). "Near Limit" on
     * the dashboard counts amber and red together, i.e. at or above {@link
     * #AMBER_AT_PCT}.
     */
    private static final int AMBER_AT_PCT = 70;

    /** At or above this, a member is in the reference tool's "hand off" zone. */
    private static final int RED_AT_PCT = 90;

    /** The storage column's scale — cost is summed across every model in a day at higher precision, then rounded once to this. */
    private static final int COST_SCALE = 4;

    /** A day's tokens can't plausibly exceed this; anything larger is a bug or an abuse — same ceiling the reference tool's own ingest route uses. */
    private static final long MAX_TOKENS_PER_DAY = 5_000_000_000L;

    /**
     * The team is KH-based (see {@code NotificationProperties}' own default
     * zone) — a single fixed zone keeps every machine's "today" identical
     * regardless of where the laptop is or how its clock is configured.
     */
    private static final ZoneId REPORT_ZONE = ZoneId.of("Asia/Phnom_Penh");

    private static final Set<String> PERIODS = Set.of("day", "week", "month");

    /** Bounded so a hand-edited query cannot walk arbitrarily far into the past. */
    private static final int MIN_OFFSET = -60;

    /** Days of history the personal "My Claude Usage" screen shows (ADR-052) — a fixed window, not a period picker like the admin dashboard's. */
    static final int MY_HISTORY_DAYS = 30;

    /** The "Usage by Model" windows the personal screen offers (ADR-058) — today, last 7 days, last 30 days. */
    static final Set<Integer> MY_RANGE_DAYS = Set.of(1, 7, 30);

    /** Used when the requested window is not one of {@link #MY_RANGE_DAYS} — a read gets a sane answer, not a 400. */
    static final int MY_DEFAULT_RANGE_DAYS = 7;

    /**
     * Model name for a day reported before per-model rows existed (V49,
     * ADR-058): its totals and cost are real, but which model they belong to
     * was never stored. Shown as its own entry rather than dropped, so the
     * by-model figures always add up to the day totals.
     */
    public static final String UNATTRIBUTED_MODEL = "unattributed";

    /** Per-model cost is kept at this scale — finer than the daily column's 4, so a cheap model's fraction of a cent survives. */
    private static final int MODEL_COST_SCALE = 6;

    /** Longest custom range the User Activity detail accepts, in days. */
    static final int MAX_ACTIVITY_RANGE_DAYS = 366;

    /** Ingest guards on the optional activity object (ADR-074) - generous, but bounded. */
    private static final int MAX_ACTIVITY_PROJECTS = 200;

    private static final int MAX_ACTIVITY_SESSIONS = 500;

    private static final int MAX_PROJECT_NAME = 100;

    private static final long SECONDS_PER_DAY = 86_400;

    private final ClaudeUsageMemberRepository members;
    private final ClaudeUsageDailyRepository daily;
    private final ClaudeUsageDailyModelRepository dailyModels;
    private final ClaudeUsageActivityRepository activities;
    private final AuditRepository audit;
    private final ObjectMapper json;

    public ClaudeUsageService(
            ClaudeUsageMemberRepository members,
            ClaudeUsageDailyRepository daily,
            ClaudeUsageDailyModelRepository dailyModels,
            ClaudeUsageActivityRepository activities,
            AuditRepository audit,
            ObjectMapper json) {
        this.members = members;
        this.daily = daily;
        this.dailyModels = dailyModels;
        this.activities = activities;
        this.audit = audit;
        this.json = json;
    }

    // -----------------------------------------------------------------------
    // Roster management
    // -----------------------------------------------------------------------

    /** Adds someone to the tracking roster, {@code invited} until their first report. */
    public ClaudeUsageMember invite(String rawEmail, String rawDisplayName, String rawTeamRole, CurrentUser actor) {
        String email = normalizeEmail(rawEmail);
        String teamRole = normalizeTeamRole(rawTeamRole);

        if (members.findByEmail(email).isPresent()) {
            throw ApiException.conflict(email + " is already tracked on this dashboard");
        }

        String displayName = displayNameOrFallback(rawDisplayName, email);
        ClaudeUsageMember created =
                members.insert(email, displayName, teamRole, ClaudeUsageMember.STATUS_INVITED, actor.id());

        audit.append(
                actor.id(),
                actor.email(),
                AuditAction.CLAUDE_USAGE_MEMBER_INVITE,
                AuditAction.TARGET_CLAUDE_USAGE_MEMBER,
                created.id(),
                json.createObjectNode().put("email", email).put("teamRole", teamRole));

        return created;
    }

    /** Changes the dashboard-scoped role (`admin`/`member`) — not {@code AppRole}, see {@link ClaudeUsageMember}. */
    public ClaudeUsageMember updateTeamRole(UUID id, String rawTeamRole, CurrentUser actor) {
        ClaudeUsageMember member = require(id);
        String teamRole = normalizeTeamRole(rawTeamRole);
        if (teamRole.equals(member.teamRole())) {
            return member;
        }

        members.updateTeamRole(id, teamRole);

        audit.append(
                actor.id(),
                actor.email(),
                AuditAction.CLAUDE_USAGE_MEMBER_ROLE_CHANGE,
                AuditAction.TARGET_CLAUDE_USAGE_MEMBER,
                id,
                json.createObjectNode()
                        .put("subject", member.email())
                        .put("from", member.teamRole())
                        .put("to", teamRole));

        return require(id);
    }

    /** Sets or clears (null) the member's weekly-equivalent soft-budget override. */
    public ClaudeUsageMember updateBudgetOverride(UUID id, Long budgetOverrideTokens, CurrentUser actor) {
        ClaudeUsageMember member = require(id);
        if (budgetOverrideTokens != null && budgetOverrideTokens <= 0) {
            throw ApiException.badRequest("budgetOverrideTokens must be a positive number of tokens, or null to clear it");
        }
        if (java.util.Objects.equals(budgetOverrideTokens, member.budgetOverrideTokens())) {
            return member;
        }

        members.updateBudgetOverride(id, budgetOverrideTokens);

        ObjectNode detail = json.createObjectNode().put("subject", member.email());
        detail.put("from", member.budgetOverrideTokens() == null ? "default" : String.valueOf(member.budgetOverrideTokens()));
        detail.put("to", budgetOverrideTokens == null ? "default" : String.valueOf(budgetOverrideTokens));

        audit.append(
                actor.id(),
                actor.email(),
                AuditAction.CLAUDE_USAGE_MEMBER_BUDGET_CHANGE,
                AuditAction.TARGET_CLAUDE_USAGE_MEMBER,
                id,
                detail);

        return require(id);
    }

    /**
     * Takes a member off the dashboard's active tracking. Never deletes the
     * row or any {@code claude_usage_daily} history (ADR-035 decision 1) — it
     * is a display decision, not a data-loss event, and does not change
     * anything about their actual Claude Code access (the mock's own framing,
     * carried into the real implementation).
     */
    public ClaudeUsageMember remove(UUID id, CurrentUser actor) {
        ClaudeUsageMember member = require(id);
        if (ClaudeUsageMember.STATUS_REMOVED.equals(member.status())) {
            return member;
        }

        members.updateStatus(id, ClaudeUsageMember.STATUS_REMOVED);

        audit.append(
                actor.id(),
                actor.email(),
                AuditAction.CLAUDE_USAGE_MEMBER_REMOVE,
                AuditAction.TARGET_CLAUDE_USAGE_MEMBER,
                id,
                json.createObjectNode().put("subject", member.email()));

        return require(id);
    }

    /**
     * Puts a removed member back on active tracking — the admin-chosen other
     * direction of {@link #remove} (ADR-035: status moves only as an admin
     * chooses, in either direction). Their history was never deleted, so it
     * is all still there. A no-op, and not re-audited, on an active member.
     */
    public ClaudeUsageMember restore(UUID id, CurrentUser actor) {
        ClaudeUsageMember member = require(id);
        if (!ClaudeUsageMember.STATUS_REMOVED.equals(member.status())) {
            return member;
        }

        members.updateStatus(id, ClaudeUsageMember.STATUS_ACTIVE);

        audit.append(
                actor.id(),
                actor.email(),
                AuditAction.CLAUDE_USAGE_MEMBER_RESTORE,
                AuditAction.TARGET_CLAUDE_USAGE_MEMBER,
                id,
                json.createObjectNode().put("subject", member.email()));

        return require(id);
    }

    private ClaudeUsageMember require(UUID id) {
        return members.findById(id).orElseThrow(() -> ApiException.notFound("claude usage member " + id));
    }

    // -----------------------------------------------------------------------
    // Ingestion — POST /api/ingest (ADR-035 decisions 2-3)
    // -----------------------------------------------------------------------

    public record IngestOutcome(String action) {
        public static final String CREATED = "created";
        public static final String UPDATED = "updated";
        /** {@link #recordScan}: the scan time was stored. */
        public static final String SCAN_RECORDED = "scan-recorded";
        /** {@link #recordScan}: no roster row for that email yet, so nothing was stored. */
        public static final String NOT_TRACKED = "not-tracked";
    }

    /**
     * Accepts one email's running total for one day and upserts it. The
     * caller (an unauthenticated-by-session reporter script, verified only by
     * the shared bearer secret at the controller) names whose day this is —
     * the secret's only job is proving the caller may write at all, not who
     * they are.
     */
    public IngestOutcome recordUsage(
            String rawEmail,
            String rawDisplayName,
            LocalDate date,
            long input,
            long output,
            long cacheRead,
            long cacheCreation,
            List<String> rawModels,
            Map<String, ClaudeUsageModelTokens> byModel) {
        return recordUsage(rawEmail, rawDisplayName, date, input, output, cacheRead, cacheCreation, rawModels, byModel, null);
    }

    /**
     * As above, with the day's activity (ADR-074) - requests, sessions,
     * active time and projects, or null from a reporter that predates it.
     * Null removes any activity stored for the day, so the detail never
     * describes a different upload than the totals beside it.
     */
    @Transactional
    public IngestOutcome recordUsage(
            String rawEmail,
            String rawDisplayName,
            LocalDate date,
            long input,
            long output,
            long cacheRead,
            long cacheCreation,
            List<String> rawModels,
            Map<String, ClaudeUsageModelTokens> byModel,
            ClaudeUsageDayActivity rawActivity) {

        String email = normalizeEmail(rawEmail);

        if (date == null) {
            throw ApiException.badRequest("date is required");
        }
        // A day of forgiveness for clock skew between whatever machine sent this
        // and this server's own clock, not an invitation to backdate arbitrarily
        // — the roster-level history is the record, not this one check.
        if (date.isAfter(LocalDate.now(REPORT_ZONE).plusDays(1))) {
            throw ApiException.badRequest("date must not be in the future");
        }
        if (input < 0 || output < 0 || cacheRead < 0 || cacheCreation < 0) {
            throw ApiException.badRequest("token counts must not be negative");
        }
        if (input + output > MAX_TOKENS_PER_DAY) {
            throw ApiException.badRequest("input + output must not exceed " + MAX_TOKENS_PER_DAY + " for a single day");
        }
        if (byModel != null) {
            for (ClaudeUsageModelTokens t : byModel.values()) {
                if (t.input() < 0 || t.output() < 0 || t.cacheReadTokens() < 0 || t.cacheCreationTokens() < 0
                        || t.cacheCreation1hTokens() < 0) {
                    throw ApiException.badRequest("byModel token counts must not be negative");
                }
                if (t.cacheCreation1hTokens() > t.cacheCreationTokens()) {
                    throw ApiException.badRequest("byModel cacheCreation1h must not exceed cacheCreation");
                }
            }
        }

        ClaudeUsageDayActivity activity = normalizeActivity(rawActivity, input, output);

        ClaudeUsageMember member = members.findByEmail(email).orElse(null);
        if (member == null) {
            // Reporting IS the opt-in this roster exists to record (ADR-035
            // decision 3) — nobody needs to have pre-invited this email.
            member = members.insert(
                    email,
                    displayNameOrFallback(rawDisplayName, email),
                    ClaudeUsageMember.ROLE_MEMBER,
                    ClaudeUsageMember.STATUS_ACTIVE,
                    null);
        } else {
            // No promotion step any more: a row reaches this table by reporting,
            // so it is already active. The branch that lifted an 'invited' row
            // went with the invite endpoint and the status itself (V36).
            //
            // A removed member is never silently reactivated by a new report —
            // status only ever changes in the direction an admin chose here.
            if (rawDisplayName != null && !rawDisplayName.isBlank()) {
                members.touchDisplayName(member.id(), rawDisplayName.trim());
            }
        }

        // byModel is the source of truth for both the display list and the
        // price the moment it is present — it is strictly richer than the
        // flat `models` names list, and pricing two sources against each
        // other would just be two ways for them to disagree.
        String models = (byModel != null && !byModel.isEmpty())
                ? joinModels(byModel.keySet())
                : joinModels(rawModels);
        BigDecimal cost = costFor(byModel);

        boolean created = daily.upsert(member.id(), date, input, output, cacheRead, cacheCreation, cost, models);
        // Same transaction, same "whole day replaces whole day" rule as the
        // upsert above (ADR-058) — the per-model rows can never drift from
        // the totals they break down, and a replay never adds a second set.
        dailyModels.replaceDay(member.id(), date, modelRowsFor(member.id(), date, byModel));
        activities.replaceDay(member.id(), date, activity);
        // An upload is a scan too (ADR-097): both times move together here.
        members.touchLastScan(member.id());
        return new IngestOutcome(created ? IngestOutcome.CREATED : IngestOutcome.UPDATED);
    }

    /**
     * A reporter scanned and found nothing new to upload (ADR-097): records
     * only when it scanned. Writes no usage row, so an unchanged day is never
     * duplicated or re-stamped, and "Last reported" keeps meaning the last
     * upload. An email with no roster row yet is left alone -- reporting
     * usage is what adds someone (ADR-035 decision 3), not checking in.
     *
     * @return {@link IngestOutcome#SCAN_RECORDED}, or {@link IngestOutcome#NOT_TRACKED} for an untracked email
     */
    @Transactional
    public IngestOutcome recordScan(String rawEmail) {
        ClaudeUsageMember member = members.findByEmail(normalizeEmail(rawEmail)).orElse(null);
        if (member == null) {
            return new IngestOutcome(IngestOutcome.NOT_TRACKED);
        }
        members.touchLastScan(member.id());
        return new IngestOutcome(IngestOutcome.SCAN_RECORDED);
    }

    /**
     * Validates the optional activity object and trims its project names.
     * Rejects rather than repairs: a malformed object means a broken reporter,
     * and storing half of it would be worse than storing none. The projects'
     * tokens are a split of the day's, so together they may not exceed it.
     */
    private static ClaudeUsageDayActivity normalizeActivity(ClaudeUsageDayActivity a, long dayInput, long dayOutput) {
        if (a == null) {
            return null;
        }
        if (a.requestCount() < 0 || a.apiCallCount() < 0 || a.conversationCount() < 0 || a.sessionCount() < 0) {
            throw ApiException.badRequest("activity counts must not be negative");
        }
        if (a.activeSeconds() < 0 || a.activeSeconds() > SECONDS_PER_DAY) {
            throw ApiException.badRequest("activity.activeSeconds must be between 0 and " + SECONDS_PER_DAY);
        }
        if (a.projects().size() > MAX_ACTIVITY_PROJECTS) {
            throw ApiException.badRequest("activity.projects must not list more than " + MAX_ACTIVITY_PROJECTS);
        }
        if (a.sessions().size() > MAX_ACTIVITY_SESSIONS) {
            throw ApiException.badRequest("activity.sessions must not list more than " + MAX_ACTIVITY_SESSIONS);
        }

        Set<String> names = new HashSet<>();
        long projectInput = 0;
        long projectOutput = 0;
        List<ClaudeUsageDayActivity.Project> projects = new ArrayList<>();
        for (ClaudeUsageDayActivity.Project p : a.projects()) {
            if (p == null) {
                throw ApiException.badRequest("activity.projects must not contain null");
            }
            String name = projectName(p.name());
            if (!names.add(name)) {
                throw ApiException.badRequest("activity.projects lists \"" + name + "\" twice");
            }
            if (p.requestCount() < 0 || p.apiCallCount() < 0 || p.conversationCount() < 0 || p.sessionCount() < 0
                    || p.activeSeconds() < 0 || p.activeSeconds() > SECONDS_PER_DAY
                    || p.inputTokens() < 0 || p.outputTokens() < 0
                    || p.cacheReadTokens() < 0 || p.cacheCreationTokens() < 0) {
                throw ApiException.badRequest("activity.projects figures must not be negative");
            }
            if (p.firstAt() != null && p.lastAt() != null && p.lastAt().isBefore(p.firstAt())) {
                throw ApiException.badRequest("activity.projects lastAt must not be before firstAt");
            }
            projectInput += p.inputTokens();
            projectOutput += p.outputTokens();
            projects.add(new ClaudeUsageDayActivity.Project(
                    name, p.requestCount(), p.apiCallCount(), p.conversationCount(), p.sessionCount(), p.activeSeconds(),
                    p.inputTokens(), p.outputTokens(), p.cacheReadTokens(), p.cacheCreationTokens(), p.firstAt(), p.lastAt()));
        }
        if (projectInput > dayInput || projectOutput > dayOutput) {
            throw ApiException.badRequest("activity.projects tokens must not exceed the day's totals");
        }

        Set<Instant> starts = new HashSet<>();
        List<ClaudeUsageDayActivity.Session> sessions = new ArrayList<>();
        for (ClaudeUsageDayActivity.Session s : a.sessions()) {
            if (s == null || s.startedAt() == null || s.endedAt() == null) {
                throw ApiException.badRequest("activity.sessions need startedAt and endedAt");
            }
            if (s.endedAt().isBefore(s.startedAt()) || s.durationSeconds() > SECONDS_PER_DAY) {
                throw ApiException.badRequest("activity.sessions must end after they start, within one day");
            }
            if (s.requestCount() < 0) {
                throw ApiException.badRequest("activity.sessions requestCount must not be negative");
            }
            if (!starts.add(s.startedAt())) {
                throw ApiException.badRequest("activity.sessions must not repeat a startedAt");
            }
            if (s.projects().size() > MAX_ACTIVITY_PROJECTS) {
                throw ApiException.badRequest("activity.sessions projects must not list more than " + MAX_ACTIVITY_PROJECTS);
            }
            List<String> sessionProjects = s.projects().stream().map(ClaudeUsageService::projectName).distinct().toList();
            sessions.add(new ClaudeUsageDayActivity.Session(s.startedAt(), s.endedAt(), s.requestCount(), sessionProjects));
        }

        return new ClaudeUsageDayActivity(
                a.requestCount(), a.apiCallCount(), a.conversationCount(), a.sessionCount(), a.activeSeconds(),
                projects, sessions);
    }

    /** A project is a folder name: trimmed, 1-100 characters, no control characters, never a path. */
    private static String projectName(String raw) {
        String name = raw == null ? "" : raw.strip();
        if (name.isEmpty() || name.length() > MAX_PROJECT_NAME) {
            throw ApiException.badRequest("activity project names must be 1-" + MAX_PROJECT_NAME + " characters");
        }
        if (name.chars().anyMatch(Character::isISOControl) || name.contains("/") || name.contains("\\")) {
            throw ApiException.badRequest("activity project names must be a folder name, not a path");
        }
        return name;
    }

    /** One row per model that actually consumed tokens, each priced on its own (ADR-058). */
    private static List<ClaudeUsageDailyModel> modelRowsFor(
            UUID memberId, LocalDate date, Map<String, ClaudeUsageModelTokens> byModel) {
        if (byModel == null || byModel.isEmpty()) {
            return List.of();
        }
        List<ClaudeUsageDailyModel> rows = new ArrayList<>();
        for (Map.Entry<String, ClaudeUsageModelTokens> entry : byModel.entrySet()) {
            String model = entry.getKey() == null ? "" : entry.getKey().trim();
            ClaudeUsageModelTokens t = entry.getValue();
            if (model.isEmpty()
                    || t.input() + t.output() + t.cacheReadTokens() + t.cacheCreationTokens() == 0) {
                continue;
            }
            BigDecimal cost = ClaudeUsagePricing.costFor(
                            model, t.input(), t.output(), t.cacheReadTokens(), t.cacheCreationTokens(),
                            t.cacheCreation1hTokens())
                    .setScale(MODEL_COST_SCALE, RoundingMode.HALF_UP);
            rows.add(new ClaudeUsageDailyModel(
                    memberId, date, model, t.input(), t.output(), t.cacheReadTokens(), t.cacheCreationTokens(),
                    t.cacheCreation1hTokens(), cost));
        }
        return rows;
    }

    /**
     * Sums each model's own real per-token cost (ADR-036). Never trusted from
     * the client — computed here so a price-table change applies without
     * redeploying every reporter.
     *
     * <p>$0 when {@code byModel} is absent or empty: a reporter that predates
     * per-model reporting still has its tokens and history recorded correctly,
     * just priced at $0 rather than a guessed number — the same trade-off the
     * reference table this was ported from makes for exactly this case.
     */
    static BigDecimal costFor(Map<String, ClaudeUsageModelTokens> byModel) {
        if (byModel == null || byModel.isEmpty()) {
            return BigDecimal.ZERO.setScale(COST_SCALE, RoundingMode.HALF_UP);
        }
        BigDecimal total = BigDecimal.ZERO;
        for (Map.Entry<String, ClaudeUsageModelTokens> entry : byModel.entrySet()) {
            ClaudeUsageModelTokens t = entry.getValue();
            total = total.add(ClaudeUsagePricing.costFor(
                    entry.getKey(), t.input(), t.output(), t.cacheReadTokens(), t.cacheCreationTokens(),
                    t.cacheCreation1hTokens()));
        }
        return total.setScale(COST_SCALE, RoundingMode.HALF_UP);
    }

    private static String joinModels(Iterable<String> rawModels) {
        if (rawModels == null) {
            return "";
        }
        List<String> collected = new ArrayList<>();
        rawModels.forEach(collected::add);
        return collected.stream()
                .filter(java.util.Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .distinct()
                .sorted()
                .collect(Collectors.joining(", "));
    }

    // -----------------------------------------------------------------------
    // Dashboard rollup
    // -----------------------------------------------------------------------

    /**
     * Turns the roster and raw daily rows into everything the Claude Usage
     * screen renders for one period. An unrecognised {@code period} falls
     * back to {@code week} and an out-of-range {@code offset} is clamped,
     * rather than either becoming a 400 — this is a read, and a hand-edited
     * query deserves a sane answer, not an error page.
     */
    public ClaudeUsageDashboard dashboard(String rawPeriod, int rawOffset) {
        return dashboard(rawPeriod, rawOffset, LocalDate.now(REPORT_ZONE), null);
    }

    /**
     * The dashboard, scoped to what {@code caller} may see.
     *
     * <p><b>An {@code admin} and above sees the team; everyone else sees only
     * their own row.</b> A plain {@code user} is on this roster because they
     * ran the reporter, and their own tokens and cost are theirs to look at —
     * but what a colleague costs is not something this product decided to tell
     * them. That is the same shape {@code NotificationFeedService} already uses
     * for the activity feed: one route, and the scope decided here from the
     * session rather than from anything the caller sends.
     *
     * <p>The scope is applied by filtering the roster and the daily rows before
     * the rollup, not by trimming the finished dashboard. Totals, the chart,
     * "active users", "near limit" and "last updated" are all derived from
     * those two lists, so narrowing the inputs narrows every figure at once —
     * there is no second code path that could disagree, and no number that
     * silently keeps counting people the caller cannot see.
     *
     * <p>Someone who has never run the reporter has no roster row, and gets an
     * empty dashboard rather than an error: nothing has gone wrong, they simply
     * have no usage yet.
     */
    public ClaudeUsageDashboard dashboardFor(CurrentUser caller, String rawPeriod, int rawOffset) {
        boolean wholeTeam = caller.role().isAtLeast(com.coc.docs.account.AppRole.ADMIN);
        return dashboard(rawPeriod, rawOffset, LocalDate.now(REPORT_ZONE), wholeTeam ? null : caller.email());
    }

    /**
     * As {@link #dashboard(String, int)}, with "today" taken as a parameter —
     * package-private so a test can fix it instead of the assertions drifting
     * with the calendar. Mirrors the reference tool's own {@code rollup(rows,
     * period, today, offset)} signature for exactly this reason.
     */
    /** The whole team, at a fixed "today". The period/range tests' entry point. */
    ClaudeUsageDashboard dashboard(String rawPeriod, int rawOffset, LocalDate today) {
        return dashboard(rawPeriod, rawOffset, today, null);
    }

    /**
     * @param onlyEmail when non-null, the one member this dashboard is about —
     *     see {@link #dashboardFor}. Null means the whole team.
     */
    ClaudeUsageDashboard dashboard(String rawPeriod, int rawOffset, LocalDate today, String onlyEmail) {
        String period = PERIODS.contains(rawPeriod) ? rawPeriod : "week";
        int offset = Math.max(MIN_OFFSET, Math.min(0, rawOffset));

        LocalDate from = rangeStart(period, today, offset);
        LocalDate to = cap(rangeEnd(period, from), today);
        long daysInRange = ChronoUnit.DAYS.between(from, to) + 1;

        List<ClaudeUsageMember> roster = members.list();
        List<ClaudeUsageDaily> rows = daily.listInRange(from, to);

        // The one place the scope is applied. Everything below is computed from
        // these two lists, so narrowing them here narrows the member table, the
        // chart, the totals and both counters together — rather than leaving a
        // "3 tracked" that quietly counts people the caller cannot see.
        if (onlyEmail != null) {
            String email = normalizeEmail(onlyEmail);
            roster = roster.stream()
                    .filter(member -> email.equals(member.email()))
                    .toList();
            Set<UUID> visible = roster.stream().map(ClaudeUsageMember::id).collect(Collectors.toSet());
            rows = rows.stream().filter(row -> visible.contains(row.memberId())).toList();
        }

        Map<UUID, List<ClaudeUsageDaily>> rowsByMember =
                rows.stream().collect(Collectors.groupingBy(ClaudeUsageDaily::memberId));
        Map<UUID, String> avatarUrls = members.avatarUrlsByMemberId();
        Map<UUID, Instant> lastScans = members.lastScanAtByMemberId();

        List<ClaudeUsageMemberUsage> memberUsages = new ArrayList<>();
        for (ClaudeUsageMember member : roster) {
            memberUsages.add(rollupMember(
                    member, rowsByMember.getOrDefault(member.id(), List.of()), daysInRange, avatarUrls.get(member.id()),
                    lastScans.get(member.id())));
        }
        memberUsages.sort((a, b) -> Long.compare(b.totalTokens(), a.totalTokens()));

        List<ClaudeUsageDailyPoint> dailyPoints = rollupDaily(from, to, rows);

        ClaudeUsageTotals totals = totalsOf(memberUsages);

        // Reported any tokens in this exact period (ADR-035, reference tool
        // parity), so switching Day/Week/Month changes this number like every
        // other figure on the dashboard. A disabled (removed) member is left
        // out, same as Near Limit below; their usage rows are untouched.
        int activeUsers = (int) memberUsages.stream()
                .filter(usage -> !ClaudeUsageMember.STATUS_REMOVED.equals(usage.member().status()))
                .filter(usage -> usage.totalTokens() > 0)
                .count();
        int nearLimit = (int) memberUsages.stream()
                .filter(usage -> !ClaudeUsageMember.STATUS_REMOVED.equals(usage.member().status()))
                .filter(usage -> !ClaudeUsageMemberUsage.STATUS_GREEN.equals(usage.budgetStatus()))
                .count();

        Instant lastUpdated =
                rows.stream().map(ClaudeUsageDaily::reportedAt).max(Instant::compareTo).orElse(null);

        return new ClaudeUsageDashboard(period, from, to, memberUsages, dailyPoints, totals, activeUsers, nearLimit, lastUpdated);
    }

    // -----------------------------------------------------------------------
    // Member activity — the admin "User Activity" detail
    // -----------------------------------------------------------------------

    /**
     * One member's reported days over the same period the dashboard is
     * showing ({@code period}/{@code offset} resolved and clamped exactly as
     * {@link #dashboard(String, int)} does), newest first, each split by model.
     *
     * <p>Read-only and admin-only (the route sits under {@code
     * /api/v1/admin/**}). Day totals are read off {@code claude_usage_daily}
     * and the split off {@code claude_usage_daily_model} unchanged — nothing
     * is re-priced or re-summed, so a day here always equals that member's
     * contribution to the dashboard's chart. A day with tokens but no
     * per-model rows (reported before V49) gets one {@link #UNATTRIBUTED_MODEL}
     * entry, the same fallback {@link #modelBreakdown} uses.
     *
     * <p>// req: FR-USAGE-001, ADR-035, ADR-058
     */
    public ClaudeUsageMemberActivity memberActivity(UUID memberId, String rawPeriod, int rawOffset) {
        return memberActivity(memberId, rawPeriod, rawOffset, LocalDate.now(REPORT_ZONE));
    }

    /** As {@link #memberActivity(UUID, String, int)}, with "today" taken as a parameter so a test can fix it. */
    ClaudeUsageMemberActivity memberActivity(UUID memberId, String rawPeriod, int rawOffset, LocalDate today) {
        return memberActivity(memberId, rawPeriod, rawOffset, null, null, null, today);
    }

    /**
     * The User Activity detail (ADR-074): {@code from}/{@code to} pick a custom
     * range (both or neither; {@code to} is capped at today), otherwise
     * {@code period}/{@code offset} resolve exactly as the dashboard's do.
     * {@code project}, when given, narrows every figure to that project.
     */
    public ClaudeUsageMemberActivity memberActivity(
            UUID memberId, String rawPeriod, int rawOffset, LocalDate rawFrom, LocalDate rawTo, String rawProject) {
        return memberActivity(memberId, rawPeriod, rawOffset, rawFrom, rawTo, rawProject, LocalDate.now(REPORT_ZONE));
    }

    ClaudeUsageMemberActivity memberActivity(
            UUID memberId,
            String rawPeriod,
            int rawOffset,
            LocalDate rawFrom,
            LocalDate rawTo,
            String rawProject,
            LocalDate today) {
        require(memberId);

        LocalDate from;
        LocalDate to;
        if (rawFrom != null || rawTo != null) {
            if (rawFrom == null || rawTo == null) {
                throw ApiException.badRequest("from and to must be given together");
            }
            if (rawTo.isBefore(rawFrom)) {
                throw ApiException.badRequest("to must not be before from");
            }
            from = rawFrom;
            to = cap(rawTo, today);
            if (to.isBefore(from)) {
                throw ApiException.badRequest("from must not be in the future");
            }
            if (ChronoUnit.DAYS.between(from, to) + 1 > MAX_ACTIVITY_RANGE_DAYS) {
                throw ApiException.badRequest("a range may cover at most " + MAX_ACTIVITY_RANGE_DAYS + " days");
            }
        } else {
            String period = PERIODS.contains(rawPeriod) ? rawPeriod : "week";
            int offset = Math.max(MIN_OFFSET, Math.min(0, rawOffset));
            from = rangeStart(period, today, offset);
            to = cap(rangeEnd(period, from), today);
        }
        String project = rawProject == null || rawProject.isBlank() ? null : rawProject.strip();

        Map<LocalDate, List<ClaudeUsageDailyModel>> modelsByDay = dailyModels.listInRangeForMember(memberId, from, to)
                .stream()
                .collect(Collectors.groupingBy(ClaudeUsageDailyModel::usageDate));
        Map<LocalDate, ClaudeUsageDayActivity> activityByDay = activities.listInRangeForMember(memberId, from, to);

        List<ClaudeUsageMemberActivity.Day> days = new ArrayList<>();
        for (ClaudeUsageDaily day : daily.listInRangeForMember(memberId, from, to)) {
            boolean hasTokens =
                    day.inputTokens() + day.outputTokens() + day.cacheReadTokens() + day.cacheCreationTokens() > 0;
            if (!hasTokens) {
                continue;
            }
            ClaudeUsageDayActivity activity = activityByDay.get(day.usageDate());

            if (project != null) {
                // Narrowed to one project: its split of the day's tokens, its own
                // sessions. Cost and models are not attributed per project, so
                // they are left out rather than estimated.
                ClaudeUsageDayActivity.Project p = activity == null ? null : activity.projects().stream()
                        .filter(candidate -> candidate.name().equals(project))
                        .findFirst()
                        .orElse(null);
                if (p == null) {
                    continue;
                }
                List<ClaudeUsageDayActivity.Session> mine = activity.sessions().stream()
                        .filter(s -> s.projects().contains(project))
                        .toList();
                ClaudeUsageDayActivity narrowed = new ClaudeUsageDayActivity(
                        p.requestCount(), p.apiCallCount(), p.conversationCount(), p.sessionCount(), p.activeSeconds(),
                        List.of(p), mine);
                days.add(new ClaudeUsageMemberActivity.Day(
                        day.usageDate(), day.reportedAt(), p.inputTokens(), p.outputTokens(), p.cacheReadTokens(),
                        p.cacheCreationTokens(), null, List.of(), narrowed));
                continue;
            }

            List<ClaudeUsageMemberActivity.ModelTokens> models = modelsByDay.getOrDefault(day.usageDate(), List.of())
                    .stream()
                    .map(m -> new ClaudeUsageMemberActivity.ModelTokens(
                            m.model(), m.inputTokens(), m.outputTokens(), m.cacheReadTokens(), m.cacheCreationTokens(),
                            m.costUsd()))
                    .toList();
            if (models.isEmpty()) {
                models = List.of(new ClaudeUsageMemberActivity.ModelTokens(
                        UNATTRIBUTED_MODEL, day.inputTokens(), day.outputTokens(), day.cacheReadTokens(),
                        day.cacheCreationTokens(), day.costUsd()));
            }
            days.add(new ClaudeUsageMemberActivity.Day(
                    day.usageDate(), day.reportedAt(), day.inputTokens(), day.outputTokens(), day.cacheReadTokens(),
                    day.cacheCreationTokens(), day.costUsd(), models, activity));
        }
        days.sort((a, b) -> b.date().compareTo(a.date()));

        return new ClaudeUsageMemberActivity(
                memberId, from, to, project, summarize(days), (int) days.stream().filter(d -> d.activity() == null).count(),
                projectTotals(days), sessionsOf(days), days);
    }

    /** Range totals: tokens over every day, activity over the days that carried it (null if none did). */
    private static ClaudeUsageMemberActivity.Summary summarize(List<ClaudeUsageMemberActivity.Day> days) {
        long input = 0;
        long output = 0;
        long cacheRead = 0;
        long cacheCreation = 0;
        boolean anyActivity = false;
        int requests = 0;
        int apiCalls = 0;
        int conversations = 0;
        int sessions = 0;
        long activeSeconds = 0;
        for (ClaudeUsageMemberActivity.Day day : days) {
            input += day.inputTokens();
            output += day.outputTokens();
            cacheRead += day.cacheReadTokens();
            cacheCreation += day.cacheCreationTokens();
            ClaudeUsageDayActivity a = day.activity();
            if (a != null) {
                anyActivity = true;
                requests += a.requestCount();
                apiCalls += a.apiCallCount();
                conversations += a.conversationCount();
                sessions += a.sessionCount();
                activeSeconds += a.activeSeconds();
            }
        }
        return anyActivity
                ? new ClaudeUsageMemberActivity.Summary(
                        requests, apiCalls, conversations, sessions, activeSeconds, input, output, cacheRead, cacheCreation)
                : new ClaudeUsageMemberActivity.Summary(null, null, null, null, null, input, output, cacheRead, cacheCreation);
    }

    /**
     * Each project summed across the range's days, largest total first.
     * {@code firstAt}/{@code lastAt} are the earliest and latest use in range.
     */
    static List<ClaudeUsageDayActivity.Project> projectTotals(List<ClaudeUsageMemberActivity.Day> days) {
        Map<String, ClaudeUsageDayActivity.Project> totals = new LinkedHashMap<>();
        for (ClaudeUsageMemberActivity.Day day : days) {
            if (day.activity() == null) {
                continue;
            }
            for (ClaudeUsageDayActivity.Project p : day.activity().projects()) {
                totals.merge(p.name(), p, (a, b) -> new ClaudeUsageDayActivity.Project(
                        a.name(),
                        a.requestCount() + b.requestCount(),
                        a.apiCallCount() + b.apiCallCount(),
                        a.conversationCount() + b.conversationCount(),
                        a.sessionCount() + b.sessionCount(),
                        a.activeSeconds() + b.activeSeconds(),
                        a.inputTokens() + b.inputTokens(),
                        a.outputTokens() + b.outputTokens(),
                        a.cacheReadTokens() + b.cacheReadTokens(),
                        a.cacheCreationTokens() + b.cacheCreationTokens(),
                        earliest(a.firstAt(), b.firstAt()),
                        latest(a.lastAt(), b.lastAt())));
            }
        }
        List<ClaudeUsageDayActivity.Project> out = new ArrayList<>(totals.values());
        out.sort((a, b) -> b.totalTokens() != a.totalTokens()
                ? Long.compare(b.totalTokens(), a.totalTokens())
                : a.name().compareTo(b.name()));
        return out;
    }

    /** Every activity session in range, newest first. */
    private static List<ClaudeUsageMemberActivity.Session> sessionsOf(List<ClaudeUsageMemberActivity.Day> days) {
        List<ClaudeUsageMemberActivity.Session> out = new ArrayList<>();
        for (ClaudeUsageMemberActivity.Day day : days) {
            if (day.activity() == null) {
                continue;
            }
            for (ClaudeUsageDayActivity.Session s : day.activity().sessions()) {
                out.add(new ClaudeUsageMemberActivity.Session(
                        day.date(), s.startedAt(), s.endedAt(), s.requestCount(), s.projects()));
            }
        }
        out.sort((a, b) -> b.startedAt().compareTo(a.startedAt()));
        return out;
    }

    private static Instant earliest(Instant a, Instant b) {
        if (a == null || b == null) {
            return Objects.requireNonNullElse(a, b);
        }
        return a.isBefore(b) ? a : b;
    }

    private static Instant latest(Instant a, Instant b) {
        if (a == null || b == null) {
            return Objects.requireNonNullElse(a, b);
        }
        return a.isAfter(b) ? a : b;
    }

    // -----------------------------------------------------------------------
    // Personal dashboard ("My Claude Usage", ADR-052)
    // -----------------------------------------------------------------------

    /**
     * Everything {@code GET /api/v1/claude-usage/me} renders: the caller's
     * own today/history, nobody else's. {@code actor} is resolved server-side
     * from the authenticated session by the controller — there is no id or
     * email parameter here to trust from the client (FR-USAGE-005, ADR-052).
     */
    public ClaudeUsageMyDashboard myDashboard(CurrentUser actor, int rangeDays) {
        return myDashboard(actor, LocalDate.now(REPORT_ZONE), rangeDays);
    }

    /** As {@link #myDashboard(CurrentUser, int)} over the default window. */
    ClaudeUsageMyDashboard myDashboard(CurrentUser actor, LocalDate today) {
        return myDashboard(actor, today, MY_DEFAULT_RANGE_DAYS);
    }

    /** As {@link #myDashboard(CurrentUser, int)}, with "today" taken as a parameter so a test can fix it. */
    ClaudeUsageMyDashboard myDashboard(CurrentUser actor, LocalDate today, int rawRangeDays) {
        String email = normalizeEmail(actor.email());
        ClaudeUsageMember member = members.findByEmail(email).orElse(null);

        LocalDate from = today.minusDays(MY_HISTORY_DAYS - 1);
        List<ClaudeUsageDaily> rows =
                member == null ? List.of() : daily.listInRangeForMember(member.id(), from, today);

        Map<LocalDate, ClaudeUsageDaily> byDate =
                rows.stream().collect(Collectors.toMap(ClaudeUsageDaily::usageDate, row -> row));

        List<ClaudeUsageMyDashboardPoint> history = new ArrayList<>();
        for (LocalDate d = from; !d.isAfter(today); d = d.plusDays(1)) {
            ClaudeUsageDaily row = byDate.get(d);
            history.add(row == null
                    ? new ClaudeUsageMyDashboardPoint(d, 0, 0)
                    : new ClaudeUsageMyDashboardPoint(d, row.inputTokens(), row.outputTokens()));
        }

        ClaudeUsageDaily todayRow = byDate.get(today);
        long todayInput = todayRow == null ? 0 : todayRow.inputTokens();
        long todayOutput = todayRow == null ? 0 : todayRow.outputTokens();

        Instant lastUpdated = rows.stream().map(ClaudeUsageDaily::reportedAt).max(Instant::compareTo).orElse(null);
        String displayName = member != null ? member.displayName() : displayNameOrFallback(actor.account().displayName(), email);

        // When the reporter last checked, separate from lastUpdated (the last upload) — ADR-097.
        Instant lastScanAt = member == null ? null : members.findLastScanAt(member.id()).orElse(null);

        ClaudeUsageMemberUsage todayUsage = member == null
                ? null
                : rollupMember(member, todayRow == null ? List.of() : List.of(todayRow), 1, actor.account().avatarUrl(), lastScanAt);

        int rangeDays = MY_RANGE_DAYS.contains(rawRangeDays) ? rawRangeDays : MY_DEFAULT_RANGE_DAYS;
        ClaudeUsageMyDashboard.ModelBreakdown breakdown = member == null
                ? ClaudeUsageMyDashboard.ModelBreakdown.empty(rangeDays, today.minusDays(rangeDays - 1), today)
                : modelBreakdown(member.id(), today, rangeDays);

        // The week's Monday is at most 6 days back, so the 30-day rows above already cover it.
        LocalDate weekFrom = today.with(DayOfWeek.MONDAY);
        long weekInput = 0;
        long weekOutput = 0;
        for (ClaudeUsageDaily row : rows) {
            if (!row.usageDate().isBefore(weekFrom) && !row.usageDate().isAfter(today)) {
                weekInput += row.inputTokens();
                weekOutput += row.outputTokens();
            }
        }
        ClaudeUsageMyDashboard.WeekUsage week = new ClaudeUsageMyDashboard.WeekUsage(
                weekFrom, today, weekInput, weekOutput,
                effectiveBudgetTokens(member == null ? null : member.budgetOverrideTokens(), 7));

        return new ClaudeUsageMyDashboard(
                email, displayName, today, todayInput, todayOutput, history, lastUpdated, todayUsage, breakdown, week,
                ClaudeUsagePricing.SUBSCRIPTION_USD_PER_MONTH, lastScanAt);
    }

    /**
     * "Usage by Model" for the last {@code rangeDays} days (ADR-058): each
     * model's four token categories kept separate and priced on its own, the
     * window's totals as their sum, and the window before it for the "from
     * previous N days" comparison — one query per table covering both windows.
     *
     * <p>A day in either window that has a {@code claude_usage_daily} row
     * but no per-model rows (reported before V49) contributes an {@link
     * #UNATTRIBUTED_MODEL} entry built from that row, so totals here always
     * equal what the daily rows say.
     */
    private ClaudeUsageMyDashboard.ModelBreakdown modelBreakdown(UUID memberId, LocalDate today, int rangeDays) {
        LocalDate from = today.minusDays(rangeDays - 1);
        LocalDate previousFrom = from.minusDays(rangeDays);

        List<ClaudeUsageDailyModel> rows =
                new ArrayList<>(dailyModels.listInRangeForMember(memberId, previousFrom, today));
        Set<LocalDate> attributedDays =
                rows.stream().map(ClaudeUsageDailyModel::usageDate).collect(Collectors.toSet());
        for (ClaudeUsageDaily day : daily.listInRangeForMember(memberId, previousFrom, today)) {
            boolean hasTokens =
                    day.inputTokens() + day.outputTokens() + day.cacheReadTokens() + day.cacheCreationTokens() > 0;
            if (hasTokens && !attributedDays.contains(day.usageDate())) {
                rows.add(new ClaudeUsageDailyModel(
                        memberId, day.usageDate(), UNATTRIBUTED_MODEL, day.inputTokens(), day.outputTokens(),
                        day.cacheReadTokens(), day.cacheCreationTokens(), 0, day.costUsd()));
            }
        }

        List<ClaudeUsageDailyModel> inRange =
                rows.stream().filter(r -> !r.usageDate().isBefore(from)).toList();
        BigDecimal previousCost = rows.stream()
                .filter(r -> r.usageDate().isBefore(from))
                .map(ClaudeUsageDailyModel::costUsd)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        Map<String, ClaudeUsageMyDashboard.ModelUsage> byModel = new LinkedHashMap<>();
        for (ClaudeUsageDailyModel r : inRange) {
            byModel.merge(r.model(), ClaudeUsageMyDashboard.ModelUsage.of(r), ClaudeUsageMyDashboard.ModelUsage::plus);
        }
        List<ClaudeUsageMyDashboard.ModelUsage> models = new ArrayList<>(byModel.values());
        models.sort((a, b) -> b.costUsd().compareTo(a.costUsd()));

        long input = 0;
        long output = 0;
        long cacheRead = 0;
        long cacheCreation = 0;
        BigDecimal cost = BigDecimal.ZERO;
        for (ClaudeUsageMyDashboard.ModelUsage m : models) {
            input += m.inputTokens();
            output += m.outputTokens();
            cacheRead += m.cacheReadTokens();
            cacheCreation += m.cacheCreationTokens();
            cost = cost.add(m.costUsd());
        }

        List<ClaudeUsageDailyModel> recent = inRange.stream()
                .sorted((a, b) -> {
                    int byDate = b.usageDate().compareTo(a.usageDate());
                    return byDate != 0 ? byDate : b.costUsd().compareTo(a.costUsd());
                })
                .toList();

        return new ClaudeUsageMyDashboard.ModelBreakdown(
                rangeDays, from, today, new ClaudeUsageTotals(input, output, cacheRead, cacheCreation, cost),
                previousCost, models, recent);
    }

    private ClaudeUsageMemberUsage rollupMember(
            ClaudeUsageMember member, List<ClaudeUsageDaily> rows, long daysInRange, String avatarUrl, Instant lastScanAt) {
        long input = 0;
        long output = 0;
        long cacheRead = 0;
        long cacheCreation = 0;
        BigDecimal cost = BigDecimal.ZERO;
        Set<String> models = new TreeSet<>();
        Instant lastActive = null;

        for (ClaudeUsageDaily row : rows) {
            input += row.inputTokens();
            output += row.outputTokens();
            cacheRead += row.cacheReadTokens();
            cacheCreation += row.cacheCreationTokens();
            cost = cost.add(row.costUsd());
            if (!row.models().isBlank()) {
                for (String model : row.models().split(",")) {
                    String trimmed = model.trim();
                    if (!trimmed.isEmpty()) {
                        models.add(trimmed);
                    }
                }
            }
            if (lastActive == null || row.reportedAt().isAfter(lastActive)) {
                lastActive = row.reportedAt();
            }
        }

        long effectiveBudget = effectiveBudgetTokens(member.budgetOverrideTokens(), daysInRange);
        long total = input + output;
        int pct = effectiveBudget > 0 ? (int) Math.round(total * 100.0 / effectiveBudget) : 0;

        return new ClaudeUsageMemberUsage(
                member, input, output, cacheRead, cacheCreation, cost, List.copyOf(models), lastActive, effectiveBudget, pct,
                budgetStatusFor(pct), avatarUrl, lastScanAt);
    }

    /** Green below {@link #AMBER_AT_PCT}, amber below {@link #RED_AT_PCT}, red at or above it. */
    private static String budgetStatusFor(int pct) {
        if (pct >= RED_AT_PCT) {
            return ClaudeUsageMemberUsage.STATUS_RED;
        }
        if (pct >= AMBER_AT_PCT) {
            return ClaudeUsageMemberUsage.STATUS_AMBER;
        }
        return ClaudeUsageMemberUsage.STATUS_GREEN;
    }

    private static List<ClaudeUsageDailyPoint> rollupDaily(LocalDate from, LocalDate to, List<ClaudeUsageDaily> rows) {
        Map<LocalDate, Long> totalsByDay = new LinkedHashMap<>();
        Map<LocalDate, Map<UUID, Long>> byMemberByDay = new LinkedHashMap<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            totalsByDay.put(d, 0L);
            byMemberByDay.put(d, new LinkedHashMap<>());
        }

        for (ClaudeUsageDaily row : rows) {
            long total = row.totalTokens();
            totalsByDay.merge(row.usageDate(), total, Long::sum);
            byMemberByDay.get(row.usageDate()).merge(row.memberId(), total, Long::sum);
        }

        List<ClaudeUsageDailyPoint> points = new ArrayList<>();
        for (Map.Entry<LocalDate, Long> entry : totalsByDay.entrySet()) {
            points.add(new ClaudeUsageDailyPoint(
                    entry.getKey(), entry.getValue(), Map.copyOf(byMemberByDay.get(entry.getKey()))));
        }
        return points;
    }

    private static ClaudeUsageTotals totalsOf(List<ClaudeUsageMemberUsage> memberUsages) {
        long input = 0;
        long output = 0;
        long cacheRead = 0;
        long cacheCreation = 0;
        BigDecimal cost = BigDecimal.ZERO;
        for (ClaudeUsageMemberUsage usage : memberUsages) {
            input += usage.inputTokens();
            output += usage.outputTokens();
            cacheRead += usage.cacheReadTokens();
            cacheCreation += usage.cacheCreationTokens();
            cost = cost.add(usage.costUsd());
        }
        return new ClaudeUsageTotals(input, output, cacheRead, cacheCreation, cost);
    }

    /**
     * The member's soft budget for a period {@code daysInRange} long, scaled
     * from their weekly-equivalent override (or the team default) — ADR-035
     * decision 5. A day view is judged against 1/7 of the week, a month
     * against ~30/7 of it, so the bar means the same thing whichever period is
     * selected.
     */
    static long effectiveBudgetTokens(Long budgetOverrideTokens, long daysInRange) {
        long weekly = budgetOverrideTokens != null ? budgetOverrideTokens : DEFAULT_SOFT_BUDGET_TOKENS;
        return Math.max(1, Math.round(weekly * daysInRange / 7.0));
    }

    private static LocalDate rangeStart(String period, LocalDate today, int offset) {
        return switch (period) {
            case "day" -> today.plusDays(offset);
            case "month" -> today.withDayOfMonth(1).plusMonths(offset);
            default -> today.with(DayOfWeek.MONDAY).plusWeeks(offset);
        };
    }

    private static LocalDate rangeEnd(String period, LocalDate from) {
        return switch (period) {
            case "day" -> from;
            case "month" -> from.withDayOfMonth(from.lengthOfMonth());
            default -> from.plusDays(6);
        };
    }

    /** The current (offset 0) period is shown only as far as it has actually happened; a past period is shown whole. */
    private static LocalDate cap(LocalDate to, LocalDate today) {
        return to.isAfter(today) ? today : to;
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

    private static String normalizeTeamRole(String raw) {
        if (raw == null || raw.isBlank()) {
            return ClaudeUsageMember.ROLE_MEMBER;
        }
        String role = raw.trim().toLowerCase(Locale.ROOT);
        if (!role.equals(ClaudeUsageMember.ROLE_ADMIN) && !role.equals(ClaudeUsageMember.ROLE_MEMBER)) {
            throw ApiException.badRequest("teamRole must be \"admin\" or \"member\"");
        }
        return role;
    }

    private static String displayNameOrFallback(String rawDisplayName, String email) {
        if (rawDisplayName != null && !rawDisplayName.isBlank()) {
            return rawDisplayName.trim();
        }
        int at = email.indexOf('@');
        return at > 0 ? email.substring(0, at) : email;
    }
}
