package com.coc.docs.claudeusage;

import java.time.LocalDate;

/**
 * One day of the "My Claude Usage" personal history (ADR-047) — unlike
 * {@link ClaudeUsageDailyPoint}, no per-member breakdown: there is only ever
 * one member in view, the caller themselves.
 */
public record ClaudeUsageMyDashboardPoint(LocalDate date, long inputTokens, long outputTokens) {

    public long totalTokens() {
        return inputTokens + outputTokens;
    }
}
