package com.coc.docs.claudeusage;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A row of {@code claude_usage_daily_model} (V49, ADR-054) — one model's share
 * of one member's day, with every token category kept separate, the way
 * Claude Code's own usage page reports it.
 */
public record ClaudeUsageDailyModel(
        UUID memberId,
        LocalDate usageDate,
        String model,
        long inputTokens,
        long outputTokens,
        long cacheReadTokens,
        long cacheCreationTokens,
        long cacheCreation1hTokens,
        BigDecimal costUsd) {}
