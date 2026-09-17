package com.coc.docs.notify;

import com.coc.docs.web.Jsonb;
import com.fasterxml.jackson.databind.JsonNode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Reads and writes {@code notification} rows.
 *
 * <p>Every method here is scoped to one account, and the account id is part of
 * the statement rather than checked beforehand. Fetch-then-check leaves a
 * window and, more importantly, leaves the ownership rule somewhere a future
 * caller can forget: written this way, reading or marking somebody else's
 * notification cannot be expressed.
 *
 * <p>// req: FR-AUTH-008, NFR-SEC-PII, ADR-034
 */
@Repository
public class NotificationRepository {

    private final JdbcClient jdbc;
    private final Jsonb jsonb;

    public NotificationRepository(JdbcClient jdbc, Jsonb jsonb) {
        this.jdbc = jdbc;
        this.jsonb = jsonb;
    }

    private Notification map(ResultSet rs, int rowNum) throws SQLException {
        OffsetDateTime read = rs.getObject("read_at", OffsetDateTime.class);
        return new Notification(
                rs.getObject("id", UUID.class),
                rs.getObject("account_id", UUID.class),
                rs.getString("type"),
                rs.getString("target_id"),
                jsonb.read(rs.getString("payload")),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                read == null ? null : read.toInstant());
    }

    /**
     * Writes one row per recipient, in one statement.
     *
     * <p>A registration notifies every active {@code super_admin}, so the fan
     * out is the normal case rather than the exception — one round trip for
     * five approvers instead of five.
     *
     * @return how many were written
     */
    public int insertFor(
            Collection<UUID> accountIds, NotificationType type, String targetId, JsonNode payload) {
        if (accountIds.isEmpty()) {
            return 0;
        }
        String body = jsonb.write(payload);
        // unnest turns the recipient list into rows, so the fan-out is the
        // database's job rather than a loop issuing one insert each.
        return jdbc.sql("""
                        insert into notification (account_id, type, target_id, payload)
                        select unnest(?::uuid[]), ?, ?, ?::jsonb
                        """)
                .params(
                        accountIds.stream().map(UUID::toString).toArray(String[]::new),
                        type.key(),
                        targetId,
                        body)
                .update();
    }

    /**
     * This account's notifications, newest first.
     *
     * <p>Own rows only — the account id comes from the resolved caller, never
     * from the request, so there is no parameter here to point at somebody
     * else (NFR-SEC-PII).
     */
    public List<Notification> findFor(UUID accountId, int limit) {
        return jdbc.sql("""
                        select id, account_id, type, target_id, payload, created_at, read_at
                        from notification
                        where account_id = ?
                        order by created_at desc
                        limit ?
                        """)
                .params(accountId, limit)
                .query(this::map)
                .list();
    }

    /** How many this account has not seen. Drives the badge. */
    public int countUnread(UUID accountId) {
        Integer count = jdbc.sql(
                        "select count(*) from notification where account_id = ? and read_at is null")
                .param(accountId)
                .query(Integer.class)
                .single();
        return count == null ? 0 : count;
    }

    /**
     * Marks one of this account's own notifications read.
     *
     * <p>Already-read rows are left alone rather than re-stamped, so the
     * timestamp keeps saying when it was first seen. Returns false when the id
     * is unknown or belongs to someone else, and the caller cannot tell those
     * apart — which is the point.
     */
    public boolean markRead(UUID id, UUID accountId, Instant now) {
        return jdbc.sql("""
                        update notification set read_at = ?
                        where id = ? and account_id = ? and read_at is null
                        """)
                .params(OffsetDateTime.ofInstant(now, ZoneOffset.UTC), id, accountId)
                .update()
                > 0;
    }

    /** Marks everything this account has not seen as read. Returns how many. */
    public int markAllRead(UUID accountId, Instant now) {
        return jdbc.sql("""
                        update notification set read_at = ?
                        where account_id = ? and read_at is null
                        """)
                .params(OffsetDateTime.ofInstant(now, ZoneOffset.UTC), accountId)
                .update();
    }

    /**
     * Deletes everything older than the cutoff, read or unread.
     *
     * <p>A bell is not an archive. {@code audit_log} is the archive — append
     * only and retained a year — and an unbounded table behind a badge count is
     * a query that gets slower for ever.
     */
    public int deleteOlderThan(Instant cutoff) {
        return jdbc.sql("delete from notification where created_at < ?")
                .param(OffsetDateTime.ofInstant(cutoff, ZoneOffset.UTC))
                .update();
    }
}
