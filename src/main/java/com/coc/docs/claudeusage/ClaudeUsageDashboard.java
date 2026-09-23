package com.coc.docs.claudeusage;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Everything the Claude Usage screen renders for one period, assembled by
 * {@link ClaudeUsageService#dashboard} (ADR-035).
 *
 * @param activeUsers members who reported any tokens in this exact period —
 *     reference-tool parity: this changes when the selected Day/Week/Month
 *     changes, unlike roster status.
 * @param nearLimit non-removed members whose {@link ClaudeUsageMemberUsage#budgetStatus()}
 *     is amber or red for this period (at or above the amber threshold).
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
