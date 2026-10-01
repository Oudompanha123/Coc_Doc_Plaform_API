package com.coc.docs.claudeusage;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * One member's usage over a date range — what the admin "User Activity" detail
 * renders: a summary, the range's projects, its activity sessions, and the
 * reported days newest first.
 *
 * <p>Two sources, kept distinct. Tokens and cost come from {@code
 * claude_usage_daily}/{@code claude_usage_daily_model}, as before. Requests,
 * sessions, active time and projects come from the activity tables (V60,
 * ADR-074), which only a reporter new enough to send them fills in — so every
 * activity figure here is nullable, and null means "not reported", never 0.
 *
 * @param project the project filter applied, or null for all projects. With a
 *     filter, token figures are that project's split of the same responses;
 *     cost and the per-model split are not attributed to projects, so a
 *     filtered day carries a null cost and no models rather than a guess.
 * @param daysWithoutActivity days in range that have tokens but were reported
 *     without activity (an older reporter) — their tokens are in the summary,
 *     their requests/sessions/time cannot be.
 *
 * <p>// req: FR-USAGE-001, ADR-035, ADR-058, ADR-074
 */
public record ClaudeUsageMemberActivity(
        UUID memberId,
        LocalDate from,
        LocalDate to,
        String project,
        Summary summary,
        int daysWithoutActivity,
        List<ClaudeUsageDayActivity.Project> projects,
        List<Session> sessions,
        List<Day> days) {

    /** Days only, no activity — the shape before ADR-074. */
    public ClaudeUsageMemberActivity(UUID memberId, LocalDate from, LocalDate to, List<Day> days) {
        this(memberId, from, to, null, Summary.EMPTY, 0, List.of(), List.of(), days);
    }

    /**
     * The range's totals. Token fields cover every reported day; the activity
     * fields are null when no day in range carried activity at all.
     */
    public record Summary(
            Integer requestCount,
            Integer apiCallCount,
            Integer conversationCount,
            Integer sessionCount,
            Long activeSeconds,
            long inputTokens,
            long outputTokens,
            long cacheReadTokens,
            long cacheCreationTokens) {

        static final Summary EMPTY = new Summary(null, null, null, null, null, 0, 0, 0, 0);

        public long totalTokens() {
            return inputTokens + outputTokens;
        }
    }

    /** One activity session in range, with the day it fell on. */
    public record Session(LocalDate date, Instant startedAt, Instant endedAt, int requestCount, List<String> projects) {

        public long durationSeconds() {
            return java.time.Duration.between(startedAt, endedAt).toSeconds();
        }
    }

    /**
     * One reported day.
     *
     * @param reportedAt when the reporter last uploaded this day's running
     *     total — the latest moment the data is known to describe, not the
     *     time any particular piece of work happened.
     * @param costUsd null when a project filter is applied (cost is not split by project)
     * @param models the day's per-model split; a day reported before V49
     *     carries one {@link ClaudeUsageService#UNATTRIBUTED_MODEL} entry built
     *     from the daily row, so the models always sum to the day. Empty under
     *     a project filter.
     * @param activity the day's requests/sessions/time/projects, or null when
     *     this day was reported without activity
     */
    public record Day(
            LocalDate date,
            Instant reportedAt,
            long inputTokens,
            long outputTokens,
            long cacheReadTokens,
            long cacheCreationTokens,
            BigDecimal costUsd,
            List<ModelTokens> models,
            ClaudeUsageDayActivity activity) {

        /** A day with no activity — the shape before ADR-074. */
        public Day(
                LocalDate date,
                Instant reportedAt,
                long inputTokens,
                long outputTokens,
                long cacheReadTokens,
                long cacheCreationTokens,
                BigDecimal costUsd,
                List<ModelTokens> models) {
            this(date, reportedAt, inputTokens, outputTokens, cacheReadTokens, cacheCreationTokens, costUsd, models, null);
        }

        /** Same definition as {@link ClaudeUsageMemberUsage#totalTokens()} — input plus output. */
        public long totalTokens() {
            return inputTokens + outputTokens;
        }
    }

    /** One model's share of a {@link Day}. */
    public record ModelTokens(
            String model,
            long inputTokens,
            long outputTokens,
            long cacheReadTokens,
            long cacheCreationTokens,
            BigDecimal costUsd) {

        public long totalTokens() {
            return inputTokens + outputTokens;
        }
    }
}
