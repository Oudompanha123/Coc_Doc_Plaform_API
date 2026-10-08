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
     * Writes one row per recipient.
     *
     * <p><b>One ordinary insert each, not one clever statement.</b> This was
     * {@code select unnest(?::uuid[])} with the recipients bound as an array —
     * a single round trip, and a binding this codebase uses nowhere else for a
     * write. It was also a statement no test had ever executed: the integration
     * suite needs Docker and skips without it, so the only coverage was a
     * mocked repository, which will agree with anything.
     *
     * <p>The saving was imaginary. {@code notifyAccount} passes one recipient
     * and {@code notifyApprovers} passes however many super admins exist —
     * single digits either way. A loop of statements every other write in this
     * package already uses is worth more than a round trip nobody would
     * measure.
     *
     * @return how many were written
     */
    public int insertFor(
            Collection<UUID> accountIds, NotificationType type, String targetId, JsonNode payload) {
        if (accountIds.isEmpty()) {
            return 0;
        }
        String body = jsonb.write(payload);
        int written = 0;
        for (UUID accountId : accountIds) {
            written += jdbc.sql("""
                            insert into notification (account_id, type, target_id, payload)
                            values (?, ?, ?, ?::jsonb)
                            """)
                    .params(accountId, type.key(), targetId, body)
                    .update();
        }
        return written;
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

    /**
     * This account's unread notifications, newest first — however old.
     *
     * <p>Read beside {@link #findFor}, because "the newest N" and "everything
     * unread" are different questions: the bell's Unread tab filters the list
     * it was given, so an unread row older than the newest N was counted by
     * the badge and shown nowhere. Same ownership rule as every reader here.
     */
    public List<Notification> findUnreadFor(UUID accountId, int limit) {
        return jdbc.sql("""
                        select id, account_id, type, target_id, payload, created_at, read_at
                        from notification
                        where account_id = ? and read_at is null
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

    /** As {@link #countUnread}, for one {@link NotificationType#key()} only. */
    public int countUnreadOfType(UUID accountId, String type) {
        Integer count = jdbc.sql(
                        "select count(*) from notification where account_id = ? and type = ? and read_at is null")
                .params(accountId, type)
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
     * Rewrites one payload field on every recipient's copy of a notification
     * about {@code targetId} — for a subject whose wording can change after it
     * was sent, which today is only an announcement edited while published
     * (ADR-098). Read state is untouched: a corrected title is not news.
     *
     * <p>Deliberately not scoped to an account, unlike every reader here. It is
     * called by the service that owns the subject, never with an id from a
     * request, and it changes wording, not who can see what.
     *
     * @return how many rows changed
     */
    public int updatePayloadFieldForTarget(NotificationType type, String targetId, String field, String value) {
        return jdbc.sql("""
                        update notification
                        set payload = jsonb_set(payload, array[?]::text[], to_jsonb(?::text))
                        where type = ? and target_id = ?
                        """)
                .params(field, value, type.key(), targetId)
                .update();
    }

    /**
     * Removes every recipient's copy of a notification about {@code targetId}
     * — for a subject that has been withdrawn, so a bell does not keep
     * offering something that no longer opens (ADR-098). Same scoping note as
     * {@link #updatePayloadFieldForTarget}.
     *
     * @return how many rows were deleted
     */
    public int deleteForTarget(NotificationType type, String targetId) {
        return jdbc.sql("delete from notification where type = ? and target_id = ?")
                .params(type.key(), targetId)
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
