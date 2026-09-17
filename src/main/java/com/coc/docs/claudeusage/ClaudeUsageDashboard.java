package com.coc.docs.claudeusage;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Everything the Claude Usage screen renders for one period, assembled by
 * {@link ClaudeUsageService#dashboard} (ADR-035).
 *
 * @param activeUsers roster members whose {@code status} is {@code active} —
 *     independent of the selected period, matching the stat card's own
 *     existing "of N tracked" framing.
 * @param nearLimit non-removed members at or above 80% of their effective
 *     budget for this period.
 * @param lastUpdated the newest {@code reported_at} among rows in range, or
 *     null if nothing was reported in this window at all.
 */
public record ClaudeUsageDashboard(
        String period,
        LocalDate from,
        LocalDate to,
        List<ClaudeUsageMemberUsage> members,
        List<ClaudeUsageDailyPoint> daily,
        ClaudeUsageTotals totals,
        int activeUsers,
        int nearLimit,
        Instant lastUpdated) {}
