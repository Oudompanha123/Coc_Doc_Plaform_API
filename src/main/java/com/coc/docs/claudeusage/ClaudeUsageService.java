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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
 * <p>// req: FR-USAGE-001, FR-USAGE-002, FR-USAGE-003, NFR-SEC-PII, ADR-035, ADR-036
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

    /** Percentage of the effective budget at or above which a member counts toward "Near Limit". */
    private static final int NEAR_LIMIT_PCT = 80;

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

    private final ClaudeUsageMemberRepository members;
    private final ClaudeUsageDailyRepository daily;
    private final AuditRepository audit;
    private final ObjectMapper json;

    public ClaudeUsageService(
            ClaudeUsageMemberRepository members,
            ClaudeUsageDailyRepository daily,
            AuditRepository audit,
            ObjectMapper json) {
        this.members = members;
        this.daily = daily;
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

    private ClaudeUsageMember require(UUID id) {
        return members.findById(id).orElseThrow(() -> ApiException.notFound("claude usage member " + id));
    }

    // -----------------------------------------------------------------------
    // Ingestion — POST /api/ingest (ADR-035 decisions 2-3)
    // -----------------------------------------------------------------------

    public record IngestOutcome(String action) {
        public static final String CREATED = "created";
        public static final String UPDATED = "updated";
    }

    /**
     * Accepts one email's running total for one day and upserts it. The
     * caller (an unauthenticated-by-session reporter script, verified only by
     * the shared bearer secret at the controller) names whose day this is —
     * the secret's only job is proving the caller may write at all, not who
     * they are.
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
            Map<String, ClaudeUsageModelTokens> byModel) {

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
                if (t.input() < 0 || t.output() < 0 || t.cacheReadTokens() < 0 || t.cacheCreationTokens() < 0) {
                    throw ApiException.badRequest("byModel token counts must not be negative");
                }
            }
        }

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
            if (ClaudeUsageMember.STATUS_INVITED.equals(member.status())) {
                members.updateStatus(member.id(), ClaudeUsageMember.STATUS_ACTIVE);
            }
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
        return new IngestOutcome(created ? IngestOutcome.CREATED : IngestOutcome.UPDATED);
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
                    entry.getKey(), t.input(), t.output(), t.cacheReadTokens(), t.cacheCreationTokens()));
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
        return dashboard(rawPeriod, rawOffset, LocalDate.now(REPORT_ZONE));
    }

    /**
     * As {@link #dashboard(String, int)}, with "today" taken as a parameter —
     * package-private so a test can fix it instead of the assertions drifting
     * with the calendar. Mirrors the reference tool's own {@code rollup(rows,
     * period, today, offset)} signature for exactly this reason.
     */
    ClaudeUsageDashboard dashboard(String rawPeriod, int rawOffset, LocalDate today) {
        String period = PERIODS.contains(rawPeriod) ? rawPeriod : "week";
        int offset = Math.max(MIN_OFFSET, Math.min(0, rawOffset));

        LocalDate from = rangeStart(period, today, offset);
        LocalDate to = cap(rangeEnd(period, from), today);
        long daysInRange = ChronoUnit.DAYS.between(from, to) + 1;

        List<ClaudeUsageMember> roster = members.list();
        List<ClaudeUsageDaily> rows = daily.listInRange(from, to);

        Map<UUID, List<ClaudeUsageDaily>> rowsByMember =
                rows.stream().collect(Collectors.groupingBy(ClaudeUsageDaily::memberId));

        List<ClaudeUsageMemberUsage> memberUsages = new ArrayList<>();
        for (ClaudeUsageMember member : roster) {
            memberUsages.add(rollupMember(member, rowsByMember.getOrDefault(member.id(), List.of()), daysInRange));
        }
        memberUsages.sort((a, b) -> Long.compare(b.totalTokens(), a.totalTokens()));

        List<ClaudeUsageDailyPoint> dailyPoints = rollupDaily(from, to, rows);

        ClaudeUsageTotals totals = totalsOf(memberUsages);

        int activeUsers = (int) roster.stream()
                .filter(member -> ClaudeUsageMember.STATUS_ACTIVE.equals(member.status()))
                .count();
        int nearLimit = (int) memberUsages.stream()
                .filter(usage -> !ClaudeUsageMember.STATUS_REMOVED.equals(usage.member().status()))
                .filter(usage -> usage.budgetPct() >= NEAR_LIMIT_PCT)
                .count();

        Instant lastUpdated =
                rows.stream().map(ClaudeUsageDaily::reportedAt).max(Instant::compareTo).orElse(null);

        return new ClaudeUsageDashboard(period, from, to, memberUsages, dailyPoints, totals, activeUsers, nearLimit, lastUpdated);
    }

    private ClaudeUsageMemberUsage rollupMember(ClaudeUsageMember member, List<ClaudeUsageDaily> rows, long daysInRange) {
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
                member, input, output, cacheRead, cacheCreation, cost, List.copyOf(models), lastActive, effectiveBudget, pct);
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
