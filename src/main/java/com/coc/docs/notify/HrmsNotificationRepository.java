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
            insert into hrms_notification (message_id, subject, report_date, received_at)
            values (?, ?, ?, ?)
            on conflict (message_id) do nothing
            """;

    private static final String RECENT_SQL =
            """
            select id, message_id, subject, report_date, received_at
            from hrms_notification
            order by received_at desc, id desc
            limit ?
            """;

    private static final String BY_ID_SQL =
            """
            select id, message_id, subject, report_date, received_at
            from hrms_notification
            where id = ?
            """;

    /**
     * The newest arrival's send time — where the next poll starts from.
     *
     * <p>The poller subtracts its lookback window from this rather than
     * trusting it exactly, so a report that arrived out of order is still
     * caught. Empty on an empty table, which the caller reads as "look back
     * from now" rather than "look back forever".
     */
    private static final String LATEST_SQL = "select max(received_at) from hrms_notification";

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
        int written = jdbc.sql(INSERT_SQL)
                .params(
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
        return jdbc.sql(RECENT_SQL).param(limit).query(HrmsNotificationRepository::map).list();
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
        return jdbc.sql(BY_ID_SQL).param(id).query(HrmsNotificationRepository::map).optional();
    }

    /** When the newest recorded arrival was sent, if there is one. */
    public Optional<Instant> latestReceivedAt() {
        return jdbc.sql(LATEST_SQL)
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
