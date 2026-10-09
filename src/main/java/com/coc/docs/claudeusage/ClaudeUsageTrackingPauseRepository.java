package com.coc.docs.claudeusage;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * When each member's tracking was paused -- {@code claude_usage_tracking_pause}
 * (V78, ADR-105). One row per disable; {@code resumedAt} is null while the
 * member is still disabled.
 *
 * <p>// req: FR-USAGE-001, FR-USAGE-002, ADR-105
 */
@Repository
public class ClaudeUsageTrackingPauseRepository {

    /** One disabled period. {@code resumedAt} null means it has not ended. */
    public record Pause(Instant disabledAt, Instant resumedAt) {}

    private final JdbcClient jdbc;

    public ClaudeUsageTrackingPauseRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Opens a pause at {@code at}. A no-op when one is already open -- the
     * partial unique index allows only one, and disabling twice must not move
     * the start of the window.
     */
    public void open(UUID memberId, Instant at) {
        jdbc.sql("""
                        insert into claude_usage_tracking_pause (member_id, disabled_at)
                        values (?, ?)
                        on conflict (member_id) where resumed_at is null do nothing
                        """)
                .params(memberId, OffsetDateTime.ofInstant(at, ZoneOffset.UTC))
                .update();
    }

    /** Closes the open pause, if any, at {@code at} -- the effective tracking-resumed time. */
    public void close(UUID memberId, Instant at) {
        jdbc.sql("""
                        update claude_usage_tracking_pause
                        set resumed_at = greatest(?, disabled_at)
                        where member_id = ? and resumed_at is null
                        """)
                .params(OffsetDateTime.ofInstant(at, ZoneOffset.UTC), memberId)
                .update();
    }

    /** Every pause this member has had, oldest first. A handful at most. */
    public List<Pause> listFor(UUID memberId) {
        return jdbc.sql("""
                        select disabled_at, resumed_at
                        from claude_usage_tracking_pause
                        where member_id = ?
                        order by disabled_at
                        """)
                .param(memberId)
                .query((rs, n) -> {
                    OffsetDateTime resumed = rs.getObject("resumed_at", OffsetDateTime.class);
                    return new Pause(
                            rs.getObject("disabled_at", OffsetDateTime.class).toInstant(),
                            resumed == null ? null : resumed.toInstant());
                })
                .list();
    }
}
