package com.coc.docs.claudeusage;

/**
 * One model's token counts within a single day's ingest (ADR-036, revision of
 * ADR-035 decision 4). Optional on ingest: a reporter that predates this
 * simply reports zero cost for the day rather than a guessed number — see
 * {@link ClaudeUsageService#recordUsage}.
 */
public record ClaudeUsageModelTokens(long input, long output, long cacheReadTokens, long cacheCreationTokens) {}
