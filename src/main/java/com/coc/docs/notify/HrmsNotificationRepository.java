package com.coc.docs.notify;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Reads and writes {@code hrms_notification}.
 *
 * <p><b>Nothing here touches {@code audit_log}</b>, which is the point of the
 * table existing — see {@code V21__hrms_notification.sql}. Unlike the audit
 * trail these rows may be deleted, and {@link #deleteReceivedBefore} is the
 * method that does it.
 *
 * <p><b>No personal data.</b> A subject line, a date and a timestamp; no
 * account is referenced and no name is stored. This is the one repository in
 * the notify package that does not carry the NFR-SEC-PII caveat, and it is
 * that way on purpose rather than by luck.
 *
 * <p>// req: FR-OPS-001, NFR-SEC-PII, ADR-006
 */
@Repository
public class HrmsNotificationRepository {

    /**
     * Records an arrival, or does nothing if it was already recorded.
     *
     * <p>{@code on conflict do nothing} rather than a select-then-insert. The
     * poller re-reads an overlapping window every run, so the conflict is the
     * expected case, not the exceptional one — and doing it in one statement
     * means two pollers racing (two replicas, or a manual run overlapping the
     * schedule) cannot both win. The unique index is the arbiter; nothing in
     * Java needs to coordinate.
     */
    private static final String INSERT_SQL =
            """
            insert into hrms_notification (owner_account_id, message_id, subject, report_date, received_at)
            values (?, ?, ?, ?, ?)
            on conflict (owner_account_id, message_id) do nothing
            """;

    /**
     * What one viewer may see: the shared mailbox's rows, plus their own.
     *
     * <p>Repeated as a fragment rather than left to callers, because this
     * predicate <em>is</em> the access rule from ADR-077 §2 and a query that
     * forgets it shows somebody another person's mail. One place to read, one
     * place to get wrong.
     *
     * <p>{@code owner_account_id is null} is the shared mailbox (V61); role
     * scoping on those rows happens above this layer.
     */
    private static final String VISIBLE_TO = "(owner_account_id is null or owner_account_id = ?)";

    private static final String RECENT_SQL =
            """
            select id, message_id, subject, report_date, received_at
            from hrms_notification
            where """
                    + VISIBLE_TO
                    + """

            order by received_at desc, id desc
            limit ?
            """;

    /**
     * One row, but only if this viewer is allowed it.
     *
     * <p>The ownership predicate is here rather than in a Java check after the
     * fetch, so "not yours" and "not there" are the same empty Optional. A
     * caller cannot accidentally act on a row it was not allowed to see,
     * and the 404 that results tells an attacker nothing about what exists.
     */
    private static final String BY_ID_SQL =
            """
            select id, message_id, subject, report_date, received_at
            from hrms_notification
            where id = ?
              and """
                    + VISIBLE_TO
                    + """

            """;

    /** Which mailbox a row came from — null for the shared one. Needed to re-read its body. */
    private static final String OWNER_OF_SQL = "select owner_account_id from hrms_notification where id = ?";

    /**
     * One mailbox's copy of a message (ADR-077).
     *
     * <p>Message-ID alone stopped identifying a row at V61: one email to five
     * people is one Message-ID in five mailboxes, so a lookup without the
     * owner returns an arbitrary one of them. Attaching a parsed body to that
     * row would file somebody's report under a colleague's name.
     *
     * <p>{@code is not distinct from} so the shared mailbox's null owner
     * matches itself.
     */
    private static final String BY_OWNER_MESSAGE_SQL =
            """
            select id, message_id, subject, report_date, received_at
            from hrms_notification
            where message_id = ?
              and owner_account_id is not distinct from ?
            """;

    /** Every arrival parsed as this calendar date's report — normally one per department, on the day HRMS sends more than one. */
    private static final String BY_REPORT_DATE_SQL =
            """
            select id, message_id, subject, report_date, received_at
            from hrms_notification
            where report_date = ?
              and """
                    + VISIBLE_TO
                    + """

            order by received_at desc, id desc
            """;

    /**
     * The newest arrival's send time — where the next poll starts from.
     *
     * <p>The poller subtracts its lookback window from this rather than
     * trusting it exactly, so a report that arrived out of order is still
     * caught. Empty on an empty table, which the caller reads as "look back
     * from now" rather than "look back forever".
     */
    /**
     * Per mailbox, not global (ADR-077).
     *
     * <p>A global maximum would let a busy inbox set the start of a quiet
     * one's next poll: account B, polled after account A received something
     * newer, would look back from A's timestamp and never see its own older
     * unread report. Each mailbox keeps its own water mark.
     *
     * <p>{@code is not distinct from} rather than {@code =} so that the shared
     * mailbox's null owner matches itself; {@code = null} is null, which
     * matches nothing and would silently reset the shared poll to "look back
     * from now" on every run.
     */
    private static final String LATEST_SQL =
            "select max(received_at) from hrms_notification where owner_account_id is not distinct from ?";

    private static final String DELETE_SQL = "delete from hrms_notification where received_at < ?";

    private final JdbcClient jdbc;

    public HrmsNotificationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @return true if this was a new arrival, false if it had already been
     *     recorded. The caller uses it to decide whether anything actually
     *     happened, which is what keeps a poll over old mail silent.
     */
    public boolean record(HrmsNotification notification) {
        return record(notification, null);
    }

    /**
     * Records an arrival as belonging to {@code owner}'s mailbox, or to the
     * shared one when {@code owner} is null (ADR-077).
     *
     * <p>Dedupe is per owner. One HRMS email sent to several people is one
     * Message-ID in several mailboxes, so a globally unique key would let the
     * first inbox polled claim it and silently discard everybody else's copy —
     * see {@code V61__hrms_notification_owner.sql}.
     */
    public boolean record(HrmsNotification notification, java.util.UUID owner) {
        int written = jdbc.sql(INSERT_SQL)
                .params(
                        owner,
                        notification.messageId(),
                        notification.subject(),
                        notification.reportDate(),
                        atUtc(notification.receivedAt()))
                .update();
        return written > 0;
    }

    /**
     * One arrival by its {@code Message-ID}, which is how a just-recorded row
     * is found again (V49/ADR-057): {@link #record} reports only whether it
     * wrote, and storing the body needs the id it wrote.
     *
     * <p>Costs one extra query per genuinely new report -- once a day, not per
     * poll -- because {@code record} returns false for the repeats that make up
     * every other run.
     */
    public Optional<HrmsNotification> findByMessageId(String messageId) {
        return jdbc.sql("""
                        select id, message_id, subject, report_date, received_at
                        from hrms_notification
                        where message_id = ?
                        """)
                .param(messageId)
                .query(HrmsNotificationRepository::map)
                .optional();
    }

    /** The most recent arrivals, newest first. */
    public List<HrmsNotification> recent(int limit) {
        return recent(limit, null);
    }

    /**
     * The newest arrivals this viewer is allowed: the shared mailbox's, plus
     * their own (ADR-077).
     *
     * <p>A null viewer sees only the shared mailbox — correct for anything
     * running without a caller, such as the digest job, which must not acquire
     * one person's private mail by being unauthenticated.
     */
    public List<HrmsNotification> recent(int limit, java.util.UUID viewer) {
        return jdbc.sql(RECENT_SQL)
                .param(viewer)
                .param(limit)
                .query(HrmsNotificationRepository::map)
                .list();
    }

    /**
     * One arrival by its surrogate key.
     *
     * <p>What the report view starts from: the row holds the {@code
     * message_id}, which is the only thing that can find the mail again.
     * Empty when the row has been pruned past the retention window, which the
     * caller reports as "no longer available" rather than as an error.
     */
    public Optional<HrmsNotification> findById(long id) {
        return findById(id, null);
    }

    /**
     * One arrival, if this viewer is allowed it (ADR-077).
     *
     * <p>Empty covers both "no such row" and "someone else's mailbox"
     * deliberately — see {@code BY_ID_SQL}.
     */
    public Optional<HrmsNotification> findById(long id, java.util.UUID viewer) {
        return jdbc.sql(BY_ID_SQL)
                .param(id)
                .param(viewer)
                .query(HrmsNotificationRepository::map)
                .optional();
    }

    /** One mailbox's copy of a message — see {@code BY_OWNER_MESSAGE_SQL}. */
    public Optional<HrmsNotification> findByMessageId(String messageId, java.util.UUID owner) {
        return jdbc.sql(BY_OWNER_MESSAGE_SQL)
                .param(messageId)
                .param(owner)
                .query(HrmsNotificationRepository::map)
                .optional();
    }

    /** Whose mailbox a row came from — empty for the shared one, or no such row. */
    public Optional<java.util.UUID> ownerOf(long id) {
        return jdbc.sql(OWNER_OF_SQL)
                .param(id)
                .query(java.util.UUID.class)
                .optional();
    }

    /** Every arrival recorded for one calendar date, newest first. */
    public List<HrmsNotification> byReportDate(LocalDate date) {
        return byReportDate(date, null);
    }

    /** The same, limited to what this viewer may see (ADR-077). */
    public List<HrmsNotification> byReportDate(LocalDate date, java.util.UUID viewer) {
        return jdbc.sql(BY_REPORT_DATE_SQL)
                .param(date)
                .param(viewer)
                .query(HrmsNotificationRepository::map)
                .list();
    }

    /** When the newest recorded arrival was sent, if there is one. */
    public Optional<Instant> latestReceivedAt() {
        return latestReceivedAt(null);
    }

    /** The newest arrival in one mailbox — where that mailbox's next poll starts. */
    public Optional<Instant> latestReceivedAt(java.util.UUID owner) {
        return jdbc.sql(LATEST_SQL)
                .param(owner)
                .query(OffsetDateTime.class)
                .optional()
                .map(OffsetDateTime::toInstant);
    }

    /**
     * Drops arrivals older than {@code cutoff}.
     *
     * @return how many rows went, for the log line that says so
     */
    public int deleteReceivedBefore(Instant cutoff) {
        return jdbc.sql(DELETE_SQL).param(atUtc(cutoff)).update();
    }

    /**
     * Instants go to the driver as UTC offsets, matching every other
     * repository here: {@code timestamptz} stores an instant, and handing the
     * driver a value with an explicit offset keeps the JVM's default zone out
     * of it entirely.
     */
    private static OffsetDateTime atUtc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static HrmsNotification map(ResultSet rs, int rowNum) throws SQLException {
        LocalDate reportDate = rs.getObject("report_date", LocalDate.class);
        return new HrmsNotification(
                rs.getLong("id"),
                rs.getString("message_id"),
                rs.getString("subject"),
                reportDate,
                rs.getObject("received_at", OffsetDateTime.class).toInstant());
    }
}
