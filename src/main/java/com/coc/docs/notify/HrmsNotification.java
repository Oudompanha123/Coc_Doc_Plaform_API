package com.coc.docs.notify;

import java.time.Instant;
import java.time.LocalDate;

/**
 * One recorded arrival of an HRMS report email.
 *
 * <p>Everything this platform keeps about a report, which is deliberately not
 * much: that one arrived, what it called itself, and when. The body — who was
 * late, who missed a check-out, sixty people's working hours — is never read
 * and never stored. {@code V21__hrms_notification.sql} sets out why at length.
 *
 * @param id the surrogate key, {@code 0} for an instance not yet written
 * @param messageId the source email's RFC 5322 {@code Message-ID}; the dedupe
 *     key, unique in the table
 * @param subject the subject as it arrived, shown verbatim in the feed
 * @param reportDate the day the report covers, or null when the subject did
 *     not parse — a degraded state the feed still displays, never a reason to
 *     drop the notification
 * @param receivedAt when the mail was sent, from its {@code Date} header
 *
 * <p>// req: FR-OPS-001, NFR-SEC-PII
 */
public record HrmsNotification(
        long id, String messageId, String subject, LocalDate reportDate, Instant receivedAt) {

    /**
     * An arrival that has not been written yet.
     *
     * <p>Carries no owner (ADR-077): which mailbox a row came from is passed
     * to {@link HrmsNotificationRepository#record} separately and enforced in
     * SQL on every read. Keeping it off this record means no query can return
     * a row to the wrong person by forgetting to check a field — the
     * predicate is in the statement, not in the caller.
     */
    public static HrmsNotification unsaved(
            String messageId, String subject, LocalDate reportDate, Instant receivedAt) {
        return new HrmsNotification(0L, messageId, subject, reportDate, receivedAt);
    }

    /**
     * The one-line summary for the notification feed.
     *
     * <p>Built from the subject rather than from {@link #reportDate}, so a
     * subject HRMS rewords tomorrow still reads correctly the day it changes.
     * The date is the parsed convenience; the subject is the truth.
     */
    public String summary() {
        return subject.isBlank() ? "An HRMS report arrived" : subject;
    }
}
