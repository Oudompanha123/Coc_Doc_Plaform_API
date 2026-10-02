package com.coc.docs.notify;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Remembers which attendance report has already been mailed to whom (V63,
 * ADR-086).
 *
 * <p>One method does the whole job, and it does it as a write rather than a
 * read: {@link #claim} inserts and reports whether this caller was the one
 * that got the row in. A {@code select} followed by an {@code insert} would
 * leave a window in which two sign-ins both see "not sent" and both send, and
 * that window is widest exactly when it matters — somebody opening the app on
 * a phone and a laptop at the same time.
 *
 * <p>// req: FR-OPS-001, ADR-086
 */
@Repository
public class AttendanceReportSentRepository {

    private final JdbcClient jdbc;

    public AttendanceReportSentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Claims the right to send this report to this account.
     *
     * @return true if the caller may send — this is the first claim. False
     *     means somebody already has it, and the caller must not send.
     */
    public boolean claim(UUID accountId, long notificationId) {
        return jdbc.sql("""
                        insert into attendance_report_sent (account_id, notification_id)
                        values (?, ?)
                        on conflict (account_id, notification_id) do nothing
                        """)
                .params(accountId, notificationId)
                .update()
                == 1;
    }

    /**
     * Gives the claim back, so a later sign-in tries again.
     *
     * <p>Called when the send failed. Without it a relay that was briefly down
     * would cost somebody that day's report permanently, which is a worse
     * outcome than the duplicate this table exists to prevent.
     */
    public void release(UUID accountId, long notificationId) {
        jdbc.sql("delete from attendance_report_sent where account_id = ? and notification_id = ?")
                .params(accountId, notificationId)
                .update();
    }
}
