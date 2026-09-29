package com.coc.docs.claudeusage;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Everything the "My Claude Usage" screen renders — the caller's own rows
 * only, resolved server-side from the session email, never from a
 * client-supplied id (ADR-052).
 *
 * @param daily the last {@link ClaudeUsageService#MY_HISTORY_DAYS} days up to
 *     and including {@code today}, one point per day, zero-filled for a day
 *     with no report.
 * @param lastUpdated the newest {@code reported_at} among rows in {@code
 *     daily}, or null if nothing was ever reported.
 * @param todayUsage the caller's own roster row rolled up over today — models,
 *     cost, and soft budget, computed exactly as the admin per-user table does
 *     for a one-day period; null if the caller is not on the roster yet.
 * @param usage "Usage by Model" over the selected window (ADR-058).
 */
public record ClaudeUsageMyDashboard(
        String email,
        String displayName,
        LocalDate today,
        long todayInputTokens,
        long todayOutputTokens,
        List<ClaudeUsageMyDashboardPoint> daily,
        Instant lastUpdated,
        ClaudeUsageMemberUsage todayUsage,
        ModelBreakdown usage) {

    public long todayTotalTokens() {
        return todayInputTokens + todayOutputTokens;
    }

    /**
     * One model's usage summed over the window — the four token categories
     * kept apart, never folded into input/output (ADR-058).
     *
     * @param cacheCreation1hTokens the part of {@code cacheCreationTokens}
     *     written at the 1-hour TTL; shown nowhere on its own, but it is why
     *     {@code costUsd} is what it is.
     */
    public record ModelUsage(
            String model,
            long inputTokens,
            long outputTokens,
            long cacheReadTokens,
            long cacheCreationTokens,
            long cacheCreation1hTokens,
            BigDecimal costUsd) {

        static ModelUsage of(ClaudeUsageDailyModel row) {
            return new ModelUsage(
                    row.model(), row.inputTokens(), row.outputTokens(), row.cacheReadTokens(),
                    row.cacheCreationTokens(), row.cacheCreation1hTokens(), row.costUsd());
        }

        ModelUsage plus(ModelUsage other) {
            return new ModelUsage(
                    model,
                    inputTokens + other.inputTokens,
                    outputTokens + other.outputTokens,
                    cacheReadTokens + other.cacheReadTokens,
                    cacheCreationTokens + other.cacheCreationTokens,
                    cacheCreation1hTokens + other.cacheCreation1hTokens,
                    costUsd.add(other.costUsd));
        }
    }

    /**
     * @param totals the sum of {@code byModel}, so the headline total always
     *     equals the per-model figures under it.
     * @param previousCostUsd the same-length window immediately before {@code
     *     from}, for the "from previous N days" comparison.
     * @param recent one entry per (day, model) in the window, newest day first.
     */
    public record ModelBreakdown(
            int rangeDays,
            LocalDate from,
            LocalDate to,
            ClaudeUsageTotals totals,
            BigDecimal previousCostUsd,
            List<ModelUsage> byModel,
            List<ClaudeUsageDailyModel> recent) {

        public static ModelBreakdown empty(int rangeDays, LocalDate from, LocalDate to) {
            return new ModelBreakdown(
                    rangeDays, from, to, new ClaudeUsageTotals(0, 0, 0, 0, BigDecimal.ZERO), BigDecimal.ZERO,
                    List.of(), List.of());
        }
    }
}
