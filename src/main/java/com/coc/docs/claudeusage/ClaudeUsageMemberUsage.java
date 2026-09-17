package com.coc.docs.claudeusage;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * One roster member, rolled up over a dashboard period (ADR-035). Pairs a
 * {@link ClaudeUsageMember} with what {@code claude_usage_daily} says about
 * them for the selected {@code [from, to]} range — zeroed out, not absent,
 * for a member who reported nothing in that window.
 *
 * @param effectiveBudgetTokens the soft budget for this exact period length,
 *     already scaled from the member's weekly-equivalent override (or the
 *     team default) — see {@code ClaudeUsageService#effectiveBudgetTokens}.
 * @param budgetPct {@code totalTokens / effectiveBudgetTokens}, as a rounded
 *     percentage. Computed here rather than left to the frontend because the
 *     scaling depends on the period's length in days, which the frontend
 *     would otherwise have to reconstruct from {@code period}/{@code offset}.
 * @param lastActiveAt the latest {@code reported_at} in range, or null if
 *     nothing was reported in this window.
 */
public record ClaudeUsageMemberUsage(
        ClaudeUsageMember member,
        long inputTokens,
        long outputTokens,
        long cacheReadTokens,
        long cacheCreationTokens,
        BigDecimal costUsd,
        List<String> modelsUsed,
        Instant lastActiveAt,
        long effectiveBudgetTokens,
        int budgetPct) {

    public long totalTokens() {
        return inputTokens + outputTokens;
    }
}
