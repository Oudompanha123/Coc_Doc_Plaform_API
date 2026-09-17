package com.coc.docs.claudeusage;

import java.math.BigDecimal;

/** Sums across every tracked member for the selected period (ADR-035). */
public record ClaudeUsageTotals(
        long inputTokens, long outputTokens, long cacheReadTokens, long cacheCreationTokens, BigDecimal costUsd) {

    public long totalTokens() {
        return inputTokens + outputTokens;
    }
}
