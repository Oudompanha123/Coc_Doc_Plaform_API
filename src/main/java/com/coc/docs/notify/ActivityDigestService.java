package com.coc.docs.notify;

import com.coc.docs.audit.AuditAction;
import com.coc.docs.audit.AuditRepository;
import com.coc.docs.auth.CurrentUser;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Builds and sends the daily content-activity digest.
 *
 * <p>One page per row, grouped by documentation section, with the rows that
 * need someone's attention highlighted — an unpublished draft left sitting, a
 * page deleted, a page edited but never published. Everything else reads as
 * ordinary.
 *
 * <p><b>Why the audit trail and not the content tables.</b> {@code page_version}
 * knows the current state but not who changed it or when within a day;
 * {@code audit_log} is the only record of the events themselves, and it is
 * already retained for a year for exactly this class of question
 * (NFR-OPS-AUDIT). The cost is that the digest reports what was <em>audited</em>,
 * so a write path that forgets to append an audit row is invisible here —
 * which is a defect in that path, and one this digest makes noticeable.
 *
 * <p>// req: FR-OPS-001, NFR-OPS-AUDIT
 */
@Service
public class ActivityDigestService {

    private static final Logger log = LoggerFactory.getLogger(ActivityDigestService.class);

    /** e.g. {@code 15-Sep-2026} — unambiguous to a reader in any locale, unlike 09/15. */
    private static final DateTimeFormatter DAY =
            DateTimeFormatter.ofPattern("dd-MMM-yyyy", Locale.ENGLISH);

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH);

    /**
     * The editor is named twice on purpose — display name and email.
     *
     * <p>Two people called "Sok" is the ordinary case here, and a digest that
     * says only "Sok" leaves a reader guessing which one; the email is the
     * thing that identifies them and the thing you would act on. Both are
     * already visible to every recipient of this mail (admins and above,
     * NFR-SEC-PII), so this shows what the audience may already see rather
     * than widening it.
     */
    private static final List<String> COLUMNS =
            List.of("Page", "Last edit", "By", "Email", "Status");

    private final ActivityDigestRepository repository;
    private final NotificationMailService mailService;
    private final ReportMailRenderer renderer;
    private final NotificationProperties properties;
    private final AuditRepository audit;
    private final ObjectMapper json;
    private final Clock clock;

    public ActivityDigestService(
            ActivityDigestRepository repository,
            NotificationMailService mailService,
            ReportMailRenderer renderer,
            NotificationProperties properties,
            AuditRepository audit,
            ObjectMapper json,
            Clock clock) {
        this.repository = repository;
        this.mailService = mailService;
        this.renderer = renderer;
        this.properties = properties;
        this.audit = audit;
        this.json = json;
        this.clock = clock;
    }

    /**
     * What a send did, for a caller that asked for one.
     *
     * <p>Returned rather than logged-and-forgotten because the admin route
     * needs to answer "did anything happen" — and "not sent" has three quite
     * different causes (nothing to report, nobody to send to, no SMTP
     * configured) that a bare {@code false} would flatten into one.
     *
     * @param sent whether a message was handed to the transport
     * @param pagesChanged how many pages the day's activity covered
     * @param sections how many doc sets those pages fell into
     * @param recipients how many addresses it was addressed to
     */
    public record DigestResult(
            LocalDate day, Outcome outcome, int pagesChanged, int sections, int recipients) {

        /** Whether a message was handed to the transport. */
        public boolean sent() {
            return outcome == Outcome.SENT;
        }
    }

    /**
     * Why a send did or did not happen.
     *
     * <p>Reported by the service rather than inferred downstream from the
     * counts: "nothing to report" and "the relay refused" both leave zero
     * messages sent, and only this class is in a position to know which
     * happened. A caller guessing from {@code pagesChanged == 0} would answer
     * "no activity" for a failed send of an empty digest.
     */
    public enum Outcome {
        /** Handed to the transport. */
        SENT,
        /** Nothing happened that day, and this deployment does not send empty digests. */
        NO_ACTIVITY,
        /** Nobody is configured to receive it and no active admin account exists. */
        NO_RECIPIENTS,
        /** Attempted and refused — no SMTP host configured, or the relay declined it. */
        NOT_DELIVERED
    }

    /**
     * Yesterday, in the configured zone.
     *
     * <p>Here rather than in the scheduled job so that the job and the admin
     * route cannot disagree about which day "the last one" is — and so the
     * zone arithmetic is testable without a scheduler.
     */
    public LocalDate yesterday() {
        return LocalDate.now(clock.withZone(properties.zoneId())).minusDays(1);
    }

    /**
     * Sends the digest for {@code day} on a person's request, and records that
     * they asked.
     *
     * <p>The audit row is appended whatever the outcome, including "not sent":
     * that someone triggered a send of other people's activity is the fact
     * worth keeping, and it is exactly the unsuccessful attempts that a trail
     * omitting them would hide.
     *
     * <p>// req: FR-OPS-001, NFR-OPS-AUDIT
     */
    public DigestResult sendDigestFor(LocalDate day, CurrentUser requestedBy) {
        DigestResult result = sendDigestFor(day);

        audit.append(
                requestedBy.id(),
                requestedBy.email(),
                AuditAction.NOTIFICATION_DIGEST_SEND,
                AuditAction.TARGET_NOTIFICATION,
                null,
                json.createObjectNode()
                        .put("day", day.toString())
                        .put("sent", result.sent())
                        .put("pagesChanged", result.pagesChanged())
                        .put("recipients", result.recipients()));

        return result;
    }

    /**
     * Builds the digest for {@code day} and sends it.
     *
     * <p>Public and day-parameterised rather than reading "yesterday" itself,
     * so that a missed day can be re-sent, and so the whole path is testable
     * without waiting for a scheduler.
     *
     * @return what the send did; never null
     */
    public DigestResult sendDigestFor(LocalDate day) {
        ZoneId zone = properties.zoneId();
        Instant from = day.atStartOfDay(zone).toInstant();
        Instant to = day.plusDays(1).atStartOfDay(zone).toInstant();

        List<PageActivity> activity = repository.pageActivityBetween(from, to);
        ReportMail report = buildReport(day, activity, zone);

        if (report.isEmpty() && !properties.sendWhenEmpty()) {
            log.info("no content activity on {} — digest not sent", day);
            return new DigestResult(day, Outcome.NO_ACTIVITY, 0, 0, 0);
        }

        List<String> recipients = resolveRecipients();
        if (recipients.isEmpty()) {
            // Decided here rather than left to the mail service's own no-op,
            // so that the caller is told which of the several reasons for
            // "nothing was sent" applies to them.
            return new DigestResult(
                    day, Outcome.NO_RECIPIENTS, activity.size(), report.sections().size(), 0);
        }

        String subject = properties.brandName() + " — content activity on " + DAY.format(day);
        boolean sent = mailService.sendBcc(
                recipients, subject, renderer.text(report), renderer.html(report));

        return new DigestResult(
                day,
                sent ? Outcome.SENT : Outcome.NOT_DELIVERED,
                activity.size(),
                report.sections().size(),
                recipients.size());
    }

    /**
     * Groups activity into the report, one section per doc set.
     *
     * <p>Package-private and separate from the send so that the layout can be
     * asserted directly, without a mail server or a mock of one standing
     * between the test and what it is checking.
     */
    ReportMail buildReport(LocalDate day, List<PageActivity> activity, ZoneId zone) {
        // Insertion-ordered: the query already returns sections in their
        // configured display order, and a digest whose sections reorder
        // between days is harder to read than one that does not.
        Map<String, List<ReportMail.Row>> bySection = new LinkedHashMap<>();
        for (PageActivity page : activity) {
            bySection.computeIfAbsent(page.sectionSlug(), key -> new ArrayList<>()).add(row(page, zone));
        }

        List<ReportMail.Section> sections = new ArrayList<>(bySection.size());
        bySection.forEach((heading, rows) -> sections.add(new ReportMail.Section(heading, COLUMNS, rows)));

        String subtitle = activity.isEmpty()
                ? DAY.format(day) + " · no pages changed"
                : DAY.format(day) + " · " + activity.size() + " page(s) changed across "
                        + sections.size() + " section(s) · times in " + zone.getId();

        return new ReportMail(
                "Content activity",
                subtitle,
                sections,
                "Highlighted rows need attention: an unpublished draft, a page deleted, or a page "
                        + "edited but never published. Sent from the audit trail, which is retained for "
                        + "at least one year.");
    }

    private static ReportMail.Row row(PageActivity page, ZoneId zone) {
        Status status = statusOf(page);
        String edits = page.eventCount() > 1 ? " (" + page.eventCount() + " edits)" : "";

        return ReportMail.Row.of(
                ReportMail.Cell.of(page.pageTitle()),
                ReportMail.Cell.baseline(CLOCK.format(page.lastAt().atZone(zone))),
                ReportMail.Cell.of(page.lastActor() + edits),
                ReportMail.Cell.muted(page.lastActorEmail()),
                new ReportMail.Cell(status.label(), status.emphasis()));
    }

    /**
     * What a reader should conclude about a page from its last event and its
     * current state.
     *
     * <p>Ordered by what a reader would want to see first when only one word
     * fits: a deletion outranks a pending draft, which outranks the fact that
     * something was published. Deriving this from current state rather than
     * from the last action alone is what makes "Draft pending" mean "there is
     * unpublished work right now" rather than "a draft was saved at some point
     * yesterday, possibly published since".
     */
    private static Status statusOf(PageActivity page) {
        if (page.pageDeleted()) {
            return new Status("Deleted", ReportMail.Emphasis.ALERT);
        }
        if (page.hasDraft()) {
            return new Status("Draft pending", ReportMail.Emphasis.ALERT);
        }
        if (!page.hasPublished()) {
            // Edited, no draft left, and nothing published: a page readers
            // cannot see. Rare, and worth a look when it happens.
            return new Status("Unpublished", ReportMail.Emphasis.ALERT);
        }
        if (AuditAction.PAGE_PUBLISH.equals(page.lastAction())) {
            return new Status("Published", ReportMail.Emphasis.NORMAL);
        }
        return new Status("Edited", ReportMail.Emphasis.NORMAL);
    }

    private record Status(String label, ReportMail.Emphasis emphasis) {}

    /**
     * The configured recipients, or every active administrator if none are
     * configured.
     *
     * <p>Falling back rather than failing: a deployment that configures SMTP
     * and forgets the recipient list still sends the digest to the people who
     * administer the system, which is the intended audience anyway.
     */
    private List<String> resolveRecipients() {
        if (!properties.recipients().isEmpty()) {
            return properties.recipients();
        }
        List<String> admins = repository.adminRecipients();
        if (admins.isEmpty()) {
            log.warn(
                    "no digest recipients: app.notifications.recipients is empty and no active admin "
                            + "account exists");
        }
        return admins;
    }
}
