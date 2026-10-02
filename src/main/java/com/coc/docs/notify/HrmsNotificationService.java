package com.coc.docs.notify;

import com.coc.docs.account.AccountRepository;
import com.coc.docs.account.AppRole;
import com.coc.docs.auth.CurrentUser;
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

    /**
     * Only for {@code account.hrms_name} (V47) — which attendance row belongs
     * to the caller. Nothing else here touches accounts.
     */
    private final AccountRepository accounts;

    /** The stored report bodies (V49, ADR-057). */
    private final HrmsReportContentRepository contents;

    /** Mails each person their own row when a report arrives. */
    private final AttendanceMailService attendanceMail;

    /** Whose inboxes may be read, and with what (ADR-077). */
    private final AccountMailCredentialRepository credentials;

    public HrmsNotificationService(
            HrmsMailbox mailbox,
            HrmsNotificationRepository repository,
            HrmsMailProperties properties,
            AccountRepository accounts,
            HrmsReportContentRepository contents,
            AttendanceMailService attendanceMail,
            AccountMailCredentialRepository credentials) {
        this.credentials = credentials;
        this.mailbox = mailbox;
        this.repository = repository;
        this.properties = properties;
        this.accounts = accounts;
        this.contents = contents;
        this.attendanceMail = attendanceMail;
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
            if (record(arrival, null, null)) {
                recorded++;
            }
        }

        if (recorded > 0) {
            log.info("HRMS: recorded {} new report notification(s) of {} message(s) seen", recorded, arrivals.size());
        }
        return recorded + pollPersonalInboxes();
    }

    /**
     * Reads the inbox of every account that has stored a credential (ADR-077).
     *
     * <p><b>This logs into other people's mail.</b> An App Password grants the
     * whole mailbox, not the HRMS corner of it; the IMAP search is restricted
     * to {@code app.hrms.sender} but that restriction is code, not permission.
     * ADR-077 §4.2 states the cost and §4.3 names the cheaper alternative that
     * should be tried first. Nothing here is implicit: an account with no
     * stored credential is never connected to.
     *
     * <p>One account's failure never stops the others. A revoked password, a
     * Google block or a changed address are per-person conditions, and a loop
     * that unwound on the first would mean one stale credential silently
     * stopping everybody else's reports.
     */
    private int pollPersonalInboxes() {
        int recorded = 0;
        int polled = 0;
        for (AccountRepository.MailTarget target : accounts.activeMailTargets()) {
            Optional<AccountMailCredentialRepository.Credential> stored = credentials.findFor(target.accountId());
            if (stored.isEmpty()) {
                continue;
            }
            HrmsMailbox.Login login =
                    new HrmsMailbox.Login(stored.get().username(), stored.get().appPassword());
            try {
                polled++;
                for (HrmsMailbox.Arrival arrival : mailbox.arrivalsSince(searchFrom(target.accountId()), login)) {
                    if (record(arrival, target.accountId(), login)) {
                        recorded++;
                    }
                }
            } catch (RuntimeException e) {
                // The mailbox already swallows IMAP failures; this catches what
                // is past it -- a parse or a database fault on one account's
                // report. Never with the credential in the message.
                log.warn("HRMS: polling one account's inbox failed ({})", e.getMessage());
            }
        }

        if (polled > 0) {
            log.info("HRMS: polled {} personal inbox(es), {} new report(s)", polled, recorded);
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
        return searchFrom(null);
    }

    /** The same, for one mailbox — each keeps its own water mark (ADR-077). */
    Instant searchFrom(java.util.UUID owner) {
        Instant now = Instant.now();
        return repository
                .latestReceivedAt(owner)
                .map(latest -> latest.minus(properties.lookback()))
                .orElseGet(() -> now.minus(properties.lookback()));
    }

    /**
     * Records one arrival; false if it had already been recorded.
     *
     * @param owner whose mailbox it came from, or null for the shared one
     * @param login the credential to re-read the body with, or null to use the
     *     configured mailbox. Must match {@code owner}: a body fetched with
     *     the wrong login is either missing or somebody else's.
     */
    private boolean record(HrmsMailbox.Arrival arrival, java.util.UUID owner, HrmsMailbox.Login login) {
        HrmsNotification notification = HrmsNotification.unsaved(
                arrival.messageId(),
                arrival.subject(),
                parseReportDate(arrival.subject()).orElse(null),
                arrival.sentAt());
        if (!repository.record(notification, owner)) {
            return false;
        }
        storeBody(arrival.messageId(), arrival.subject(), owner, login);
        return true;
    }

    /**
     * Fetches and stores the parsed body of a just-recorded report
     * (V49, ADR-057).
     *
     * <p>Runs once per genuinely new report, not per poll: {@code record}
     * returns false for the repeats that make up every other run, so the
     * mailbox is opened for a body about once a day rather than every thirty
     * minutes.
     *
     * <p><b>Never fails the poll.</b> A body that cannot be fetched or parsed
     * leaves the arrival recorded with no content, which the read path handles
     * by falling back to the mailbox. Losing the notification because the
     * mailbox hiccuped mid-parse would be a worse outcome than storing nothing,
     * and this is the one place that trade-off is decided.
     */
    /**
     * One message's body, from whichever mailbox holds it.
     *
     * <p>A null login means the configured mailbox. An owned report exists
     * only in its owner's inbox, so reading it with the shared credential
     * would find nothing — and reading it with the <em>wrong</em> person's
     * credential is the mistake this single choke point exists to prevent.
     */
    private Optional<String> bodyOf(String messageId, HrmsMailbox.Login login) {
        return login == null ? mailbox.htmlBodyOf(messageId) : mailbox.htmlBodyOf(messageId, login);
    }

    private void storeBody(String messageId, String subject, java.util.UUID owner, HrmsMailbox.Login login) {
        try {
            repository
                    .findByMessageId(messageId, owner)
                    .ifPresent(saved -> bodyOf(messageId, login)
                            .map(html -> HrmsReportParser.parse(subject, saved.reportDate(), html))
                            .ifPresent(content -> {
                                contents.save(saved.id(), content);
                                mailEachPersonTheirOwnRow(content);
                            }));
        } catch (RuntimeException e) {
            log.warn("HRMS: recorded {} but could not store its body: {}", subject, e.getMessage());
        }
    }

    /**
     * Sends every matched person their own row, once, when the report first
     * arrives.
     *
     * <p>Here rather than in {@link #report}: a read must not send mail, or
     * opening the same report twice mails everybody twice and a screen refresh
     * becomes a broadcast. This runs on the poll, in the branch that only a
     * genuinely new arrival reaches.
     *
     * <p>Wrapped separately from the store above, and swallowing its own
     * failures: a mail relay that is down must not lose the stored body, and
     * the body is the thing that cannot be recovered later. Mail can be sent
     * again; a report pruned from the mailbox cannot be re-read.
     */
    private void mailEachPersonTheirOwnRow(HrmsMailContent content) {
        try {
            attendanceMail.sendPersonalReports(content);
        } catch (RuntimeException e) {
            log.warn("attendance: stored {} but could not mail it out: {}", content.subject(), e.getMessage());
        }
    }

    /** The most recent recorded arrivals from the shared mailbox, newest first. */
    public List<HrmsNotification> recent(int limit) {
        return recent(limit, null);
    }

    /**
     * The most recent arrivals this viewer may see: the shared mailbox's, plus
     * the ones read from their own inbox (ADR-077).
     *
     * <p>This is what puts a person's own report behind the bell. Without the
     * viewer the feed shows the shared mailbox only, which is what every
     * caller without a session gets.
     */
    public List<HrmsNotification> recent(int limit, java.util.UUID viewer) {
        return limit <= 0 ? List.of() : repository.recent(limit, viewer);
    }

    /**
     * One report's contents, fetched from the mailbox on demand.
     *
     * <p><b>Read, shown, dropped.</b> Nothing here writes the body anywhere:
     * not to {@code hrms_notification}, which {@code V21} keeps deliberately
     * narrow, not to {@code audit_log}, and not to a log line. The row in the
     * database still holds only a subject, a date and an arrival time; this
     * method is the one path by which the rest of the report is ever visible,
     * and it exists for exactly as long as the response does. {@code ADR-042}
     * has the decision and what it weighed.
     *
     * <p>Costs one IMAP connection per call, which is why nothing calls it on
     * a schedule and the poll still reads envelopes only. A person opening a
     * report is a deliberate act and can wait for a round trip; a background
     * job doing it sixty times a day would be the mailbox being mined.
     *
     * @return empty when the notification is unknown, when the mail is no
     *     longer in the mailbox, or when the mailbox cannot be reached. The
     *     caller cannot tell these apart and should not: all three mean "not
     *     available now", and distinguishing them would tell an unauthorised
     *     caller which ids exist.
     */
    public Optional<HrmsMailContent> report(long id) {
        return report(id, null);
    }

    /**
     * The same, limited to what {@code viewer} is allowed to open (ADR-077).
     *
     * <p>A report taken from an account's own inbox is readable by that
     * account alone — role is not consulted, and a {@code super_admin} asking
     * for somebody else's gets the same empty Optional as a stranger. The
     * filter is in the SQL, so "not yours" and "no such id" are indistinguishable
     * from outside.
     *
     * <p>A null viewer sees only the shared mailbox, which is correct for the
     * callers that have no session.
     */
    public Optional<HrmsMailContent> report(long id, java.util.UUID viewer) {
        Optional<HrmsNotification> found = repository.findById(id, viewer);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        HrmsNotification notification = found.get();

        // Stored first (V49, ADR-057): no IMAP round trip, and it still answers
        // after the mail is deleted from the mailbox or the credential rotates.
        Optional<HrmsMailContent> stored =
                contents.findByNotificationId(notification.id(), notification.subject(), notification.reportDate());
        if (stored.isPresent()) {
            return stored;
        }

        // The reports recorded before V49 have no stored body, so the original
        // on-demand path stays as the fallback rather than those becoming
        // permanently unreadable. Needs the mailbox, hence the usable() check
        // here and not above -- a stored report is readable with HRMS
        // unconfigured, which it was not before.
        if (!properties.usable()) {
            return Optional.empty();
        }
        Optional<HrmsMailContent> fetched = mailbox
                .htmlBodyOf(notification.messageId())
                .map(html -> HrmsReportParser.parse(
                        notification.subject(), notification.reportDate(), html));

        // Backfill: a report recorded before V49 is stored the first time
        // somebody opens it, so the fallback heals itself instead of being a
        // permanent second path. Only reports someone actually looked at are
        // stored, which is a narrower widening than walking the whole table
        // would be -- ADR-057's cost arrives gradually and only for reports
        // that were going to be read anyway.
        //
        // Failing to store must not fail the read: the caller asked for a
        // report, not for a write, and they have already got one.
        fetched.ifPresent(content -> {
            try {
                contents.save(notification.id(), content);
            } catch (RuntimeException e) {
                log.warn("HRMS: served report {} from the mailbox but could not store it: {}",
                        notification.id(), e.getMessage());
            }
        });
        return fetched;
    }

    /**
     * Every report recorded for one calendar date — normally one per
     * department, on the day HRMS sends more than one. Backs the admin
     * Attendance page's date+department picker, which needs to know what
     * departments exist for a date before a person can even choose one;
     * there is no cheaper, metadata-only way to learn a report's department
     * (see {@link HrmsReportParser#parse} — it is only known once a body is
     * parsed).
     *
     * <p>Unscoped, unlike {@link #reportFor} — this exists only for the
     * admin Attendance page, already floored at {@code admin} in {@code
     * SecurityConfig}, so it hands back the mail as sent rather than
     * narrowing per caller the way a lower-floored route would have to.
     *
     * <p>An arrival whose content is unavailable (never stored, and now gone
     * from the mailbox or unreachable) is silently left out rather than
     * failing the whole date — the same "empty means not available now"
     * contract as {@link #report}, just applied per-item instead of to the
     * one result.
     */
    public List<HrmsMailContent> reportsForDate(LocalDate date) {
        return repository.byReportDate(date).stream().flatMap(n -> contentOf(n).stream()).toList();
    }

    /**
     * One notification's content, stored body first (V49, ADR-057) and the
     * mailbox only as the pre-V49 fallback — the same order {@link #report}
     * uses, just factored out so {@link #reportsForDate} can call it once per
     * arrival without duplicating the fallback logic.
     */
    private Optional<HrmsMailContent> contentOf(HrmsNotification notification) {
        Optional<HrmsMailContent> stored =
                contents.findByNotificationId(notification.id(), notification.subject(), notification.reportDate());
        if (stored.isPresent()) {
            return stored;
        }
        if (!properties.usable()) {
            return Optional.empty();
        }
        return mailbox
                .htmlBodyOf(notification.messageId())
                .map(html -> HrmsReportParser.parse(notification.subject(), notification.reportDate(), html));
    }

    /**
     * The report as one caller is entitled to see it.
     *
     * <p>An {@code admin} and above gets the mail as it was sent — the whole
     * department, which is what the mail itself is. Every other role gets only
     * the row naming them, because a plain {@code user} opening this is one
     * employee looking up their own clock-in, and the rest of the table is
     * other people's attendance (NFR-SEC-PII).
     *
     * <p><b>The link is explicit and nothing infers it.</b> The mail identifies
     * a person by a name cell and nothing else, so matching runs against
     * {@code account.hrms_name} (V47), set deliberately by a super admin.
     * {@code display_name} is not used: it is edited freely by its owner, and a
     * rename would silently change whose attendance somebody sees. Comparison
     * is case- and whitespace-insensitive, which absorbs the spacing HRMS puts
     * in its own cells without loosening it into a fuzzy match — a near-miss
     * here shows one employee's times to a different employee.
     *
     * <p>An unlinked caller gets the report with <b>no rows</b> rather than an
     * empty {@link Optional}: the distinction matters to the screen, which can
     * then say the account is not linked to an attendance record instead of
     * claiming the report could not be read. {@code details} and {@code note}
     * survive the narrowing — a leave-request mail carries no rows at all, and
     * withholding its body from the person it concerns would be scoping by
     * accident rather than on purpose.
     *
     * <p>Scope comes from the session and only from the session; there is no
     * parameter on the route for a caller to widen (TM-025).
     *
     * <p>// req: FR-OPS-001, NFR-SEC-PII, ADR-042
     */
    public Optional<ScopedReport> reportFor(long id, CurrentUser caller) {
        // Viewer first (ADR-077): a report from somebody's own inbox is not
        // openable by anyone else, and that is settled before role is looked
        // at. An admin's elevated view below applies to the shared mailbox's
        // reports, which is all it ever applied to.
        Optional<HrmsMailContent> full = report(id, caller == null ? null : caller.id());
        if (caller != null && caller.isAtLeast(AppRole.ADMIN)) {
            return full.map(content -> new ScopedReport(content, false, true));
        }
        String explicitName = caller == null
                ? null
                : accounts.hrmsNameOf(caller.id()).orElse(null);
        String email = caller == null ? null : caller.email();

        return full.map(content -> {
            HrmsMailContent mine = content.onlyRowsFor(explicitName, email);
            // "Linked" now means the caller could be resolved to a row at all,
            // by either route. It stopped meaning "an admin typed a name" when
            // the address became the primary key: an account with no
            // hrms_name is the normal case, not an unconfigured one.
            boolean linked = (explicitName != null && !explicitName.isBlank()) || !mine.rows().isEmpty();
            return new ScopedReport(mine, true, linked);
        });
    }

    /**
     * The caller's own attendance row for one calendar date, from whichever
     * department's report actually names them (self-service, ADR-084).
     *
     * <p>Unlike {@link #reportsForDate} — which is unscoped and exists only
     * for the admin page already floored at {@code admin} — this always
     * narrows to the caller, the same way {@link #reportFor} narrows one
     * report by id. Every notification {@code caller} may see for the date
     * (the shared mailbox's, plus their own inbox's, ADR-077 via {@link
     * HrmsNotificationRepository#byReportDate(LocalDate, java.util.UUID)}) is
     * resolved and matched against {@code account.hrms_name} or email; one
     * employee belongs to one department, so the first report that actually
     * names them is the answer.
     *
     * <p>Always returns, never refuses: a caller with nothing recorded for the
     * date is not an error, and a caller with no link at all is not either —
     * both are ordinary outcomes the screen must tell apart, via {@code
     * viewerLinked} and an empty {@code rows()}, not via absence of a result.
     * {@code null} is reported as not linked and not found, the shape a caller
     * with no session (which cannot reach this route anyway, per {@code
     * SecurityConfig}) would get if it somehow did.
     *
     * <p>Scope comes from the session and only from the session; there is no
     * parameter on the route for a caller to widen (TM-025), same as {@link
     * #reportFor}.
     *
     * <p>// req: FR-OPS-001, NFR-SEC-PII, ADR-084
     */
    public ScopedReport myAttendanceFor(LocalDate date, CurrentUser caller) {
        String explicitName = caller == null ? null : accounts.hrmsNameOf(caller.id()).orElse(null);
        String email = caller == null ? null : caller.email();

        if (caller != null) {
            for (HrmsNotification notification : repository.byReportDate(date, caller.id())) {
                Optional<HrmsMailContent> content = contentOf(notification);
                if (content.isEmpty()) {
                    continue;
                }
                HrmsMailContent mine = content.get().onlyRowsFor(explicitName, email);
                if (!mine.rows().isEmpty()) {
                    return new ScopedReport(mine, true, true);
                }
            }
        }

        // Nothing recorded for the date named this caller. Still honest about
        // which of the two reasons that is -- an explicit hrms_name is taken
        // as linked even though today's silence cannot re-confirm it from a
        // row, the same reading reportFor already gives that field.
        boolean linked = explicitName != null && !explicitName.isBlank();
        HrmsMailContent empty = new HrmsMailContent(null, date, null, List.of(), List.of(), null);
        return new ScopedReport(empty, true, linked);
    }

    /**
     * A report plus the two facts the screen needs to describe it honestly.
     *
     * <p>Both flags exist because zero rows has three different causes and they
     * are not interchangeable: the parser lost, the caller is not linked to any
     * name in the mail, or the caller is linked and simply is not in this
     * report. Returning only the content collapsed the first two, and the UI
     * blamed HRMS for a layout change every time somebody unlinked opened a
     * report — a false accusation against somebody else's system.
     *
     * @param content the report, already narrowed if {@code scopedToViewer}
     * @param scopedToViewer whether {@code content.rows()} was filtered to the
     *     caller. False for an admin and above, who get the mail as sent
     * @param viewerLinked whether the caller's account carries an
     *     {@code hrms_name} at all. Always true for an admin, who needs no
     *     link to read the whole table
     */
    public record ScopedReport(HrmsMailContent content, boolean scopedToViewer, boolean viewerLinked) {}

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
     * "yesterday, probably" is not.
     *
     * <p><b>A null is ordinary, not a fault.</b> The first real poll, on
     * 2026-09-17, found that {@code noreply-hrms@wecambodia.com} also sends
     * "[KOSIGN] Your leave request has been accepted" — three of the ten
     * messages it returned. One sender, several kinds of notice, and only one
     * of them carries a date. So nulls are expected in steady state, and a
     * column that is <em>suddenly all</em> null is the signal worth noticing —
     * not the presence of nulls at all.
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
