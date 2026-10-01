package com.coc.docs.claudeusage;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * One member's activity on one day (V60, ADR-074): how often, for how long and
 * on which projects — the part of the User Activity detail a token total
 * cannot answer. Computed by the reporter from the same transcripts it sums
 * tokens from, and stored as sent; see V60 for the definitions.
 *
 * @param requestCount human turns (distinct Claude Code {@code promptId}s)
 * @param apiCallCount deduplicated model responses — the unit tokens are summed over
 * @param conversationCount distinct Claude Code {@code sessionId}s
 * @param sessionCount blocks of continuous activity, split after 30 minutes idle
 * @param activeSeconds the sum of each session's last event minus its first
 *
 * <p>// req: FR-USAGE-001, ADR-074
 */
public record ClaudeUsageDayActivity(
        int requestCount,
        int apiCallCount,
        int conversationCount,
        int sessionCount,
        long activeSeconds,
        List<Project> projects,
        List<Session> sessions) {

    public ClaudeUsageDayActivity {
        projects = projects == null ? List.of() : List.copyOf(projects);
        sessions = sessions == null ? List.of() : List.copyOf(sessions);
    }

    /**
     * One project's share of the day. {@code sessionCount}/{@code activeSeconds}
     * are computed from this project's own events, so two projects worked on
     * in parallel each get the overlapping time — project durations can add up
     * to more than the day's, which never double-counts.
     */
    public record Project(
            String name,
            int requestCount,
            int apiCallCount,
            int conversationCount,
            int sessionCount,
            long activeSeconds,
            long inputTokens,
            long outputTokens,
            long cacheReadTokens,
            long cacheCreationTokens,
            Instant firstAt,
            Instant lastAt) {

        /** Same definition as {@link ClaudeUsageMemberUsage#totalTokens()} — input plus output. */
        public long totalTokens() {
            return inputTokens + outputTokens;
        }
    }

    /** One continuous-activity block and the projects active in it. */
    public record Session(Instant startedAt, Instant endedAt, int requestCount, List<String> projects) {

        public Session {
            projects = projects == null ? List.of() : List.copyOf(projects);
        }

        public long durationSeconds() {
            return Duration.between(startedAt, endedAt).toSeconds();
        }
    }
}
