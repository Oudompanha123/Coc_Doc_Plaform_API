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
 * @param budgetStatus {@link #STATUS_GREEN}/{@link #STATUS_AMBER}/{@link
 *     #STATUS_RED}, from {@code budgetPct} against {@code ClaudeUsageService}'s
 *     thresholds — the one place that maps a percentage to a status, so the
 *     "Near Limit" stat, the table's progress bar, and the "hand off" nudge
 *     can never disagree about which zone a member is in.
 * @param lastActiveAt the latest {@code reported_at} in range, or null if
 *     nothing was reported in this window.
 * @param avatarUrl the profile picture of the platform {@code account} with
 *     the same email, or null when there is no such account or it has no
 *     picture — the frontend shows initials then.
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
        int budgetPct,
        String budgetStatus,
        String avatarUrl) {

    /** Comfortably under budget. */
    public static final String STATUS_GREEN = "green";
    /** At or above the amber threshold — the reference tool's "pay attention" zone. */
    public static final String STATUS_AMBER = "amber";
    /** At or above the red threshold — the reference tool's "hand off" zone. */
    public static final String STATUS_RED = "red";

    public long totalTokens() {
        return inputTokens + outputTokens;
    }
}
