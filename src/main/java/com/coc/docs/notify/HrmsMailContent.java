package com.coc.docs.notify;

import java.time.LocalDate;
import java.util.List;

/**
 * One HRMS attendance report, parsed from the mail and held only long enough
 * to answer the request that asked for it.
 *
 * <p><b>Never persisted.</b> {@code V21__hrms_notification.sql} keeps the
 * report body out of this database on purpose — it names ~60 colleagues and
 * their working hours — and that still holds. This record is built on a click,
 * serialised to the caller, and dropped. Nothing writes it to a table, a log
 * or a cache, and {@code toString} is left to the record default only because
 * nothing logs one; if that ever changes, this is the type whose fields must
 * not reach a log line.
 *
 * <p>See {@code ADR-042} for the decision that lets it exist at all, and for
 * what was weighed: the platform now shows this data on request, without
 * holding it.
 *
 * @param subject the mail's subject, which is what the feed row already shows
 * @param reportDate the day the report covers, or null when the subject did
 *     not parse — the table is still worth showing without it
 * @param department the heading above the table, e.g. "BeplePay Department".
 *     Blank when the mail had none, which is not an error worth failing on.
 * @param rows one line per person, in the order the mail listed them —
 *     deliberately not re-sorted, because the sender's order is the one the
 *     reader recognises from the email itself
 */
public record HrmsMailContent(
        String subject,
        LocalDate reportDate,
        String department,
        List<Row> rows,
        List<Detail> details,
        String note) {

    public HrmsMailContent {
        subject = subject == null ? "" : subject;
        department = department == null ? "" : department;
        rows = rows == null ? List.of() : List.copyOf(rows);
        details = details == null ? List.of() : List.copyOf(details);
        note = note == null ? "" : note;
    }

    /**
     * One label/value pair from the mails that are not attendance reports.
     *
     * <p>HRMS sends more than the daily table: leave and overtime requests
     * accepted or rejected, an account created, a password reset. Those are all
     * the same shape — a heading, four or so labelled fields, a footer — and
     * the feed lists them beside the reports, so the popup has to render them
     * or it says "could not be read" for most of what it is asked to open.
     *
     * <p>Label and value both, rather than a formatted line, because the
     * rendering belongs to the screen: a phone wants them stacked and a desktop
     * wants two columns, and a pre-joined {@code "From: 08-Sep"} can be neither.
     */
    public record Detail(String label, String value) {

        public Detail {
            label = label == null ? "" : label;
            value = value == null ? "" : value;
        }
    }

    /**
     * One person's line.
     *
     * @param name as the mail spells it, never normalised — this is somebody's
     *     name and the sender is the authority on it
     */
    public record Row(String name, Entry yesterdayCheckOut, Entry todayCheckIn) {}

    /**
     * One check, as the mail presents it: what was scheduled, what happened,
     * and what HRMS called the difference.
     *
     * <p>All three are strings, and that is deliberate. {@code scheduled} and
     * {@code actual} are wall-clock labels this platform only ever passes
     * through — parsing them to {@code LocalTime} would mean picking a zone
     * this system does not know and cannot infer, to gain nothing, and would
     * turn HRMS writing "00:00" for "never happened" into either a wrong time
     * or a dropped row.
     *
     * @param status HRMS's own words — "Normal", "Late Check-In", "Missed
     *     Check-Out". Passed through rather than mapped to an enum so a status
     *     this build has never heard of still reaches the screen.
     */
    public record Entry(String scheduled, String actual, String status) {

        public Entry {
            scheduled = scheduled == null ? "" : scheduled;
            actual = actual == null ? "" : actual;
            status = status == null ? "" : status;
        }
    }

    /**
     * Whether anything at all was read out of the mail.
     *
     * <p>Both halves, because a mail is one or the other: an attendance report
     * has rows and no details, a leave request has details and no rows. Only
     * when neither came back has the parser genuinely failed, and only then
     * should the screen say so — checking {@code rows} alone is what made every
     * leave-request mail report itself as unreadable.
     */
    public boolean isEmpty() {
        return rows.isEmpty() && details.isEmpty();
    }

    /** The heading the mail gave itself — a department for a report, "Leave Request" for the rest. */
    public String heading() {
        return department;
    }
}
