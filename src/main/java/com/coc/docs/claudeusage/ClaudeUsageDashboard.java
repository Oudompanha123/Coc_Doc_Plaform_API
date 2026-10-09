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
 * @param members active members only. A disabled (removed) member is in
 *     {@code disabledMembers} instead, and nothing else here counts them:
 *     not the chart, the totals, either counter or {@code lastUpdated}.
 * @param lastUpdated the newest {@code reported_at} among active members'
 *     rows in range, or null if none reported in this window at all.
 * @param disabledMembers members an admin has disabled, with their usage for
 *     the period still rolled up -- listed only so they can be found and
 *     enabled again, never counted (ADR-035).
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
        Instant lastUpdated,
        List<ClaudeUsageMemberUsage> disabledMembers) {}
