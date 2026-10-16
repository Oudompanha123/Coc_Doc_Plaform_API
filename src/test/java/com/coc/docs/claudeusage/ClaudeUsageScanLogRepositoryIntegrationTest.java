package com.coc.docs.claudeusage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.coc.docs.PostgresTestBase;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The running → finished lifecycle of a scan log row against real Postgres
 * (V80, ADR-107): a start inserts a running row, the result completes that same
 * row, and neither a resent result nor a late start ever changes a finished one.
 * The upsert's {@code where} clause and V80's check constraint are what hold
 * these rules, so they are tested where they live, not through a mock.
 *
 * <p>// req: FR-USAGE-002, ADR-107
 */
class ClaudeUsageScanLogRepositoryIntegrationTest extends PostgresTestBase {

    private static final Instant STARTED = Instant.parse("2026-10-07T04:00:01Z");

    @Autowired ClaudeUsageScanLogRepository logs;
    @Autowired JdbcClient jdbc;

    @BeforeEach
    void clean() {
        jdbc.sql("delete from claude_usage_scan_log").update();
    }

    private static ClaudeUsageScanLog running(UUID scanId, String email) {
        return new ClaudeUsageScanLog(null, scanId, null, email, null, null, "scheduled", "0.6.0", STARTED, null, STARTED,
                "running", "not-attempted", null, null, null, null, null, null, null, List.of());
    }

    private static ClaudeUsageScanLog finished(UUID scanId, String email, String status, Long newTokens) {
        return new ClaudeUsageScanLog(null, scanId, null, email, null, null, "scheduled", "0.6.0", STARTED,
                STARTED.plusSeconds(8), STARTED, status, "uploaded", 10, 12, 0, newTokens, 1,
                "failed".equals(status) ? "upload" : null, "failed".equals(status) ? "HTTP 503" : null, List.of());
    }

    private ClaudeUsageScanLog only() {
        List<ClaudeUsageScanLog> rows = logs.list(STARTED.minusSeconds(60), STARTED.plusSeconds(60), 10);
        assertThat(rows).hasSize(1);
        return rows.get(0);
    }

    @Test
    @DisplayName("a start inserts a running row; its result completes the same row")
    void startThenResult() {
        UUID scanId = UUID.randomUUID();
        assertThat(logs.insertRunning(running(scanId, "dana@example.com"))).isTrue();
        assertThat(only().status()).isEqualTo("running");
        assertThat(only().finishedAt()).isNull();

        assertThat(logs.insert(finished(scanId, "dana@example.com", "success", 500L), "[]")).isTrue();

        ClaudeUsageScanLog row = only();
        assertThat(row.status()).isEqualTo("success");
        assertThat(row.finishedAt()).isEqualTo(STARTED.plusSeconds(8));
        assertThat(row.newTokens()).isEqualTo(500L);
    }

    @Test
    @DisplayName("a resent result and a late start leave a finished row as it was")
    void finishedRowNeverChanges() {
        UUID scanId = UUID.randomUUID();
        assertThat(logs.insert(finished(scanId, "dana@example.com", "failed", null), "[]")).isTrue();

        assertThat(logs.insert(finished(scanId, "dana@example.com", "success", 999L), "[]")).isFalse();
        assertThat(logs.insertRunning(running(scanId, "dana@example.com"))).isFalse();

        assertThat(only().status()).isEqualTo("failed");
        assertThat(only().newTokens()).isNull();
    }

    @Test
    @DisplayName("another email cannot complete someone's running row")
    void otherEmailCannotComplete() {
        UUID scanId = UUID.randomUUID();
        logs.insertRunning(running(scanId, "dana@example.com"));

        assertThat(logs.insert(finished(scanId, "mallory@example.com", "success", 1L), "[]")).isFalse();
        assertThat(only().status()).isEqualTo("running");
    }

    @Test
    @DisplayName("V80: a running row must have no finish time")
    void runningRowHasNoFinishTime() {
        assertThatThrownBy(() -> jdbc.sql("""
                        insert into claude_usage_scan_log (client_scan_id, email, trigger, started_at, finished_at,
                          status, upload_status)
                        values (?, 'a@example.com', 'manual', now(), now(), 'running', 'not-attempted')
                        """).param(UUID.randomUUID()).update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("listForEmail returns only that email's scans")
    void listForEmail() {
        logs.insertRunning(running(UUID.randomUUID(), "dana@example.com"));
        logs.insertRunning(running(UUID.randomUUID(), "sam@example.com"));

        assertThat(logs.listForEmail("dana@example.com", STARTED.minusSeconds(60), STARTED.plusSeconds(60), 10))
                .extracting(ClaudeUsageScanLog::email)
                .containsExactly("dana@example.com");
    }
}
