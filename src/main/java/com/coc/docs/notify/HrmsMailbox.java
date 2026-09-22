package com.coc.docs.notify;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The one thing this feature needs from a mailbox: what has arrived from one
 * sender lately.
 *
 * <p>An interface because the implementation talks IMAP to Google, and there
 * is no honest way to unit-test against that. {@link HrmsNotificationService}
 * holds every decision worth testing — what counts as new, what the subject
 * means, what gets written — and takes one of these, so those decisions are
 * tested against a list rather than against a network.
 *
 * <p><b>The contract is envelopes, not mail.</b> An implementation returns a
 * message id, a subject and a send time, and is expected not to fetch bodies
 * at all: see {@code V21__hrms_notification.sql} for why the report's contents
 * are deliberately not in this system. An implementation that started
 * returning bodies would not break anything here, which is exactly why it is
 * written down.
 *
 * <p>// req: FR-OPS-001, NFR-SEC-PII
 */
public interface HrmsMailbox {

    /**
     * One message's envelope.
     *
     * @param messageId the RFC 5322 {@code Message-ID}, globally unique by
     *     construction and used as the dedupe key. Never null or blank — an
     *     implementation that finds a message without one must drop it rather
     *     than invent one, because an invented id defeats the unique index
     *     that stops duplicate notifications.
     * @param subject the subject exactly as received, never null; a message
     *     with no subject yields an empty string, which is a thing that
     *     arrived and is still worth saying so
     * @param sentAt the {@code Date} header — when it was sent, not when it
     *     was found
     */
    record Arrival(String messageId, String subject, Instant sentAt) {}

    /**
     * Envelopes from the configured sender sent at or after {@code since},
     * oldest first.
     *
     * <p>Callers re-read an overlapping window every run and rely on the
     * database to reject what they have seen before, so returning a message
     * twice is expected and harmless. Returning one <em>late</em> is not: an
     * implementation must not skip a message because it was already read, or
     * flagged, or seen on a previous call.
     *
     * <p>Never throws for an unreachable mailbox or a refused credential.
     * Those are conditions to log and retry on the next schedule, not
     * exceptions for a scheduled job to unwind on — the caller has nothing
     * better to do with them. An empty list means "nothing to report", which
     * covers both "no new mail" and "could not look".
     */
    List<Arrival> arrivalsSince(Instant since);

    /**
     * The HTML body of one message, fetched on demand.
     *
     * <p><b>This is the exception to "envelopes, not mail" above, and it is a
     * narrow one.</b> Polling still never reads a body: this is called only
     * when a person opens one report, for that one message, and what comes
     * back is parsed, sent to them, and dropped. Nothing writes it down —
     * {@code V21__hrms_notification.sql} keeps the body out of the database and
     * that has not changed. {@code ADR-042} records the decision.
     *
     * <p>Empty when the message cannot be found or read: it may have been
     * deleted from the mailbox since it was recorded, which is ordinary rather
     * than exceptional — the notification row outlives the mail. Callers show
     * "could not read it" and leave the row alone.
     *
     * <p>Never throws, for the same reason {@link #arrivalsSince} does not.
     *
     * @param messageId the RFC 5322 {@code Message-ID} recorded when the
     *     arrival was first seen
     */
    Optional<String> htmlBodyOf(String messageId);
}
