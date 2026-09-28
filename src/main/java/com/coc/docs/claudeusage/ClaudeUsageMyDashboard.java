package com.coc.docs.claudeusage;

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
 */
public record ClaudeUsageMyDashboard(
        String email,
        String displayName,
        LocalDate today,
        long todayInputTokens,
        long todayOutputTokens,
        List<ClaudeUsageMyDashboardPoint> daily,
        Instant lastUpdated) {

    public long todayTotalTokens() {
        return todayInputTokens + todayOutputTokens;
    }
}
