package com.coc.docs.claudeusage;

/**
 * One model's token counts within a single day's ingest (ADR-036, revision of
 * ADR-035 decision 4). Optional on ingest: a reporter that predates this
 * simply reports zero cost for the day rather than a guessed number — see
 * {@link ClaudeUsageService#recordUsage}.
 *
 * @param cacheCreation1hTokens the part of {@code cacheCreationTokens} written
 *     with the 1-hour TTL (ADR-054); 0 from a reporter that predates the
 *     split, which then prices every write as 5-minute.
 */
public record ClaudeUsageModelTokens(
        long input, long output, long cacheReadTokens, long cacheCreationTokens, long cacheCreation1hTokens) {

    /** No 1-hour split — every cache write is taken as 5-minute. */
    public ClaudeUsageModelTokens(long input, long output, long cacheReadTokens, long cacheCreationTokens) {
        this(input, output, cacheReadTokens, cacheCreationTokens, 0);
    }
}
