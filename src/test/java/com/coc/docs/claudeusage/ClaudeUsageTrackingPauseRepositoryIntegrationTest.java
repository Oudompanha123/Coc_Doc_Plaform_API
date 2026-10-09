package com.coc.docs.claudeusage;

import static org.assertj.core.api.Assertions.assertThat;

import com.coc.docs.PostgresTestBase;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@link ClaudeUsageTrackingPauseRepository} against a real PostgreSQL with
 * V78 applied -- the partial unique index behind "one open pause per member"
 * and the {@code on conflict ... where resumed_at is null} that relies on it.
 *
 * <p>// req: FR-USAGE-001, ADR-105
 */
class ClaudeUsageTrackingPauseRepositoryIntegrationTest extends PostgresTestBase {

    @Autowired ClaudeUsageTrackingPauseRepository pauses;
    @Autowired ClaudeUsageMemberRepository members;
    @Autowired JdbcClient jdbc;

    private UUID memberId;

    @BeforeEach
    void setUp() {
        jdbc.sql("delete from claude_usage_tracking_pause").update();
        jdbc.sql("delete from claude_usage_member where email = 'pause-it@example.com'").update();
        memberId = members.insert("pause-it@example.com", "Pause", ClaudeUsageMember.ROLE_MEMBER,
                ClaudeUsageMember.STATUS_ACTIVE, null).id();
    }

    @Test
    @DisplayName("open twice keeps one open pause and its first start; close stamps it; a later disable is a new row")
    void lifecycle() {
        Instant first = Instant.parse("2026-10-01T03:00:00Z");
        pauses.open(memberId, first);
        pauses.open(memberId, first.plus(1, ChronoUnit.HOURS));

        assertThat(pauses.listFor(memberId)).singleElement().satisfies(p -> {
            assertThat(p.disabledAt()).isEqualTo(first);
            assertThat(p.resumedAt()).isNull();
        });

        Instant resumed = first.plus(2, ChronoUnit.DAYS);
        pauses.close(memberId, resumed);
        pauses.close(memberId, resumed.plus(1, ChronoUnit.DAYS)); // nothing open: no change

        Instant again = resumed.plus(5, ChronoUnit.DAYS);
        pauses.open(memberId, again);

        assertThat(pauses.listFor(memberId)).hasSize(2);
        assertThat(pauses.listFor(memberId).get(0).resumedAt()).isEqualTo(resumed);
        assertThat(pauses.listFor(memberId).get(1).disabledAt()).isEqualTo(again);
        assertThat(pauses.listFor(memberId).get(1).resumedAt()).isNull();
    }

    @Test
    @DisplayName("a resume earlier than the disable is clamped, never stored backwards")
    void resumeNeverBeforeDisable() {
        Instant start = Instant.parse("2026-10-01T03:00:00Z");
        pauses.open(memberId, start);
        pauses.close(memberId, start.minusSeconds(60));

        assertThat(pauses.listFor(memberId).get(0).resumedAt()).isEqualTo(start);
    }
}
