package com.coc.docs.claudeusage;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A row of {@code claude_usage_daily} (V22, ADR-035) — one member's token
 * usage for one day, upserted by {@code POST /api/ingest}.
 */
public record ClaudeUsageDaily(
        UUID id,
        UUID memberId,
        LocalDate usageDate,
        long inputTokens,
        long outputTokens,
        long cacheReadTokens,
        long cacheCreationTokens,
        BigDecimal costUsd,
        String models,
        Instant reportedAt) {

    public long totalTokens() {
        return inputTokens + outputTokens;
    }
}
