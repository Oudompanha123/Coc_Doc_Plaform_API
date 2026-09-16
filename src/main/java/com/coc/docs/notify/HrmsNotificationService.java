package com.coc.docs.notify;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Turns "an email arrived" into "the bell has something in it".
 *
 * <p>Every decision worth testing lives here rather than in
 * {@link HrmsImapMailbox}: how far back to look, what the subject means, and
 * what counts as new. The mailbox is an interface for exactly that reason —
 * these are tested against a list of arrivals, never against Google.
 *
 * <p>// req: FR-OPS-001, NFR-SEC-PII
 */
@Service
public class HrmsNotificationService {

    private static final Logger log = LoggerFactory.getLogger(HrmsNotificationService.class);

    /**
     * The date inside a subject like {@code Attendance on 08-Sep-2026}.
     *
     * <p>Matched anywhere in the string rather than anchored to a fixed
     * wording. HRMS is somebody else's system: it may prepend a department, add
     * a prefix, or switch "Attendance on" for "Attendance report for" without
     * telling anyone, and none of those should cost a notification. What it is
     * unlikely to change is the shape of the date itself.
     */
    private static final Pattern SUBJECT_DATE = Pattern.compile("(\\d{1,2}-[A-Za-z]{3}-\\d{4})");

    /**
     * {@code Locale.ENGLISH}, explicitly, and this is not decoration.
     *
     * <p>The default locale on a machine in this project is Korean (the same
     * setting behind CONST-OPS-01), and {@code MMM} parses month names in the
     * locale it is given. With the default, {@code Sep} does not parse and
     * every report silently lands with a null date. The email is English
     * because HRMS writes it in English; the parser is pinned to match.
     *
     * <p>{@link ResolverStyle#STRICT} for the same class of reason. The default
     * is SMART, which does not reject an impossible date — it clamps it, so
     * {@code 31-Feb-2026} parses happily as 28 February. A date silently moved
     * three days is worse than no date at all, and this is a report whose
     * entire subject is a date. STRICT is why the year pattern is {@code uuuu}
     * and not {@code yyyy}: strict resolution refuses a year-of-era with no era
     * to go with it, which is the error every first attempt at this hits.
     */
    private static final DateTimeFormatter SUBJECT_DATE_FORMAT = DateTimeFormatter.ofPattern("d-MMM-uuuu", Locale.ENGLISH)
            .withResolverStyle(ResolverStyle.STRICT);

    private final HrmsMailbox mailbox;
    private final HrmsNotificationRepository repository;
    private final HrmsMailProperties properties;

    public HrmsNotificationService(
            HrmsMailbox mailbox, HrmsNotificationRepository repository, HrmsMailProperties properties) {
        this.mailbox = mailbox;
        this.repository = repository;
        this.properties = properties;
    }

    /**
     * Looks for new reports and records the ones not seen before.
     *
     * @return how many were new. Zero is the ordinary result: the report
     *     arrives once a day and this runs every half hour.
     */
    public int poll() {
        if (!properties.usable()) {
            return 0;
        }

        List<HrmsMailbox.Arrival> arrivals = mailbox.arrivalsSince(searchFrom());
        int recorded = 0;
        for (HrmsMailbox.Arrival arrival : arrivals) {
            if (record(arrival)) {
                recorded++;
            }
        }

        if (recorded > 0) {
            log.info("HRMS: recorded {} new report notification(s) of {} message(s) seen", recorded, arrivals.size());
        }
        return recorded;
    }

    /**
     * Where this run's search starts.
     *
     * <p>The lookback window is subtracted from the newest arrival already
     * recorded, not from now. Starting at "now minus the window" would be the
     * same thing while the poller is keeping up and quietly wrong after any
     * outage longer than the window — which is the one time it matters. On an
     * empty table there is nothing to anchor to, so it falls back to the window
     * before now: a fresh installation announces the last week's reports once,
     * which is a defensible thing for a new feature to do.
     *
     * <p>Re-reading an overlap on every run is the design, not waste. The
     * poller keeps no bookmark; the unique index on {@code message_id} is what
     * makes a repeat harmless.
     */
    Instant searchFrom() {
        Instant now = Instant.now();
        return repository
                .latestReceivedAt()
                .map(latest -> latest.minus(properties.lookback()))
                .orElseGet(() -> now.minus(properties.lookback()));
    }

    /** Records one arrival; false if it had already been recorded. */
    private boolean record(HrmsMailbox.Arrival arrival) {
        HrmsNotification notification = HrmsNotification.unsaved(
                arrival.messageId(),
                arrival.subject(),
                parseReportDate(arrival.subject()).orElse(null),
                arrival.sentAt());
        return repository.record(notification);
    }

    /** The most recent recorded arrivals, newest first. */
    public List<HrmsNotification> recent(int limit) {
        return limit <= 0 ? List.of() : repository.recent(limit);
    }

    /**
     * Deletes arrivals past the retention window.
     *
     * @return how many rows went
     */
    public int prune() {
        return repository.deleteReceivedBefore(Instant.now().minus(properties.retention()));
    }

    /**
     * The day a subject says it covers, if it says.
     *
     * <p>Empty rather than a guess when the subject does not match. A wrong
     * date on an attendance report is worse than no date: it is the one field
     * a reader would act on, and "no date shown" is visibly incomplete in a way
     * "yesterday, probably" is not. A run of nulls in the column is also the
     * signal that HRMS changed its wording.
     */
    static Optional<LocalDate> parseReportDate(String subject) {
        if (subject == null || subject.isBlank()) {
            return Optional.empty();
        }
        Matcher matcher = SUBJECT_DATE.matcher(subject);
        if (!matcher.find()) {
            return Optional.empty();
        }
        try {
            return Optional.of(LocalDate.parse(matcher.group(1), SUBJECT_DATE_FORMAT));
        } catch (DateTimeParseException e) {
            // Shape matched, value did not — 31-Feb-2026, or a month
            // abbreviation that is not one. Same answer as no match at all.
            return Optional.empty();
        }
    }
}
