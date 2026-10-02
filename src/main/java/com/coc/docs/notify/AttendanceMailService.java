package com.coc.docs.notify;

import com.coc.docs.account.AccountRepository;
import jakarta.mail.internet.MimeMessage;
import java.util.Optional;
import java.util.UUID;
import org.springframework.mail.javamail.MimeMessageHelper;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Emails each person their own attendance, and nobody else's.
 *
 * <p>HRMS sends one mail to the whole department: every recipient sees every
 * colleague's clock-in, clock-out and who was late. This sends the same facts
 * the other way round — one mail per person, carrying the single row that
 * names them. It does not forward the HRMS mail, and it never carries a second
 * person's row, so a delivery that goes to the wrong address discloses one
 * row rather than sixty (NFR-SEC-PII).
 *
 * <p><b>Who gets one is decided by {@link HrmsMailContent#onlyRowsFor}</b>, the
 * same matcher the in-app view uses: the account's email address against the
 * row's name tokens, with {@code account.hrms_name} as the override. Nothing
 * here re-implements that rule — one matcher, one answer, whether somebody is
 * reading the screen or the mail.
 *
 * <p>A person with no row in the report is sent nothing at all. Silence is the
 * right answer: "you were not in today's report" is not news, and a daily mail
 * saying it would train everybody to ignore the ones that matter.
 *
 * <p>The markup is {@link ReportMail}, not a hand-written string — that type
 * exists precisely so a second report is a set of rows rather than a second
 * copy of a document, and its own Javadoc names the three mailers in
 * {@code auth} that each carry their own near-identical HTML as the lesson.
 *
 * <p>// req: FR-OPS-001, NFR-SEC-PII, ADR-032, ADR-057
 */
@Service
public class AttendanceMailService {

    private static final Logger log = LoggerFactory.getLogger(AttendanceMailService.class);

    private static final DateTimeFormatter DAY =
            DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

    /** Named for the reader, not for the database: "Check-Out" is what the mail they already get calls it. */
    private static final List<String> COLUMNS =
            List.of("", "Scheduled", "Actual", "Status");

    private final AccountRepository accounts;
    private final NotificationMailService mailService;
    private final ReportMailRenderer renderer;
    private final NotificationProperties properties;

    /** An account's own credential, when it has supplied one (ADR-063). */
    private final AccountMailCredentialRepository credentials;

    private final PersonalMailSenderFactory personalSenders;

    /** Mints the access token an OAuth credential sends with (ADR-085). */
    private final GoogleMailOAuthService oauth;

    public AttendanceMailService(
            AccountRepository accounts,
            NotificationMailService mailService,
            ReportMailRenderer renderer,
            NotificationProperties properties,
            AccountMailCredentialRepository credentials,
            PersonalMailSenderFactory personalSenders,
            GoogleMailOAuthService oauth) {
        this.oauth = oauth;
        this.accounts = accounts;
        this.mailService = mailService;
        this.renderer = renderer;
        this.properties = properties;
        this.credentials = credentials;
        this.personalSenders = personalSenders;
    }

    /**
     * Sends one mail per person named in {@code report}, to whichever accounts
     * match them.
     *
     * @return how many were sent. Zero is an ordinary result — a report whose
     *     people have no accounts here, which is the normal case until the
     *     people in these reports are users of this platform
     */
    public int sendPersonalReports(HrmsMailContent report) {
        if (report.rows().isEmpty()) {
            // A leave-request mail, or one the parser could not read. Neither
            // has per-person rows to split, and guessing would be worse.
            return 0;
        }

        int sent = 0;
        for (AccountRepository.MailTarget target : accounts.activeMailTargets()) {
            HrmsMailContent mine = report.onlyRowsFor(target.hrmsName(), target.email());
            if (mine.rows().isEmpty()) {
                continue;
            }
            if (send(target.accountId(), target.email(), mine)) {
                sent++;
            }
        }

        if (sent > 0) {
            log.info("attendance: sent {} personal report(s) for {}", sent, report.subject());
        }
        return sent;
    }

    /**
     * Mails one person one report, outside the poll (ADR-089).
     *
     * <p>The same send the poll uses, exposed for the sign-in mailer. Routed
     * through the identical path on purpose: the choice of sender, the one
     * address per message rule and the fallback all live in {@link #send}, and
     * a second entry point that reimplemented any of them would drift from it.
     *
     * @return whether it was sent. False means the caller should not record it
     *     as delivered.
     */
    public boolean sendReportTo(UUID accountId, String email, HrmsMailContent content) {
        return send(accountId, email, content);
    }

    private boolean send(UUID accountId, String email, HrmsMailContent mine) {
        ReportMail mail = build(mine);
        String subject = properties.brandName() + " — your attendance"
                + (mine.reportDate() == null ? "" : " on " + DAY.format(mine.reportDate()));

        // Their own credential if they supplied one (ADR-063), the shared
        // sender otherwise. The fallback is the point: opting out must not
        // cost somebody their attendance mail.
        Optional<AccountMailCredentialRepository.Credential> own =
                personalSenders.isUsable() ? credentials.findFor(accountId) : Optional.empty();
        if (own.isPresent()) {
            return sendAsSelf(own.get(), email, subject, mail);
        }

        // One address per message, never a list: a bcc of forty people would
        // put forty different rows in one send, and the first mistake in the
        // recipient list would be the wrong person's hours.
        return mailService.send(List.of(email), subject, renderer.text(mail), renderer.html(mail));
    }

    /**
     * Sends through the account's own SMTP session, From and envelope both.
     *
     * <p>A failure here falls back to the shared sender rather than dropping
     * the mail: a stale App Password is the likeliest fault, and somebody
     * whose credential expired should still learn they were late.
     */
    private boolean sendAsSelf(
            AccountMailCredentialRepository.Credential credential,
            String email,
            String subject,
            ReportMail mail) {
        try {
            // Empty for an OAuth grant Google will not honour (ADR-085).
            // Treated exactly like a send failure below: the shared sender
            // still delivers, and somebody whose grant lapsed should still
            // learn they were late.
            var maybeSender = personalSenders.senderOrEmpty(credential, oauth);
            if (maybeSender.isEmpty()) {
                log.warn(
                        "attendance: {} has no usable mail grant, falling back to the shared sender",
                        credential.username());
                return mailService.send(List.of(email), subject, renderer.text(mail), renderer.html(mail));
            }
            var sender = maybeSender.get();
            MimeMessage message = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            // Gmail rewrites a From that is not the authenticated account, so
            // this must be the credential's own address and not the recipient
            // row's -- they are the same person, but only one of them is what
            // the relay will accept.
            helper.setFrom(credential.username());
            helper.setTo(email);
            helper.setSubject(subject);
            helper.setText(renderer.text(mail), renderer.html(mail));
            sender.send(message);
            return true;
        } catch (Exception e) {
            log.warn(
                    "attendance: could not send as {} ({}), falling back to the shared sender",
                    credential.username(),
                    e.getMessage());
            return mailService.send(List.of(email), subject, renderer.text(mail), renderer.html(mail));
        }
    }

    private ReportMail build(HrmsMailContent mine) {
        List<ReportMail.Section> sections = new ArrayList<>();
        for (HrmsMailContent.Row row : mine.rows()) {
            sections.add(new ReportMail.Section(
                    row.name(),
                    COLUMNS,
                    List.of(
                            line("Yesterday check-out", row.yesterdayCheckOut()),
                            line("Today check-in", row.todayCheckIn()))));
        }

        String subtitle = mine.department().isBlank()
                ? "As reported by HRMS"
                : mine.department() + " · as reported by HRMS";

        return new ReportMail(
                "Your attendance",
                subtitle,
                sections,
                // Says where it came from and where to argue with it. This
                // application only reads the report; it does not own the times
                // and cannot correct them.
                "These times come from HRMS, not from " + properties.brandName()
                        + ". Raise anything that looks wrong with HR — this platform only reads the report.");
    }

    /**
     * One check as a row: what was scheduled, what happened, and what HRMS
     * called it.
     *
     * <p>Only the status is emphasised, and only when it is not "Normal". A
     * mail that highlights every cell highlights nothing, and the reader is
     * looking for the one word that is not "Normal".
     */
    private static ReportMail.Row line(String label, HrmsMailContent.Entry entry) {
        String status = entry.status();
        return ReportMail.Row.of(
                ReportMail.Cell.muted(label),
                ReportMail.Cell.baseline(entry.scheduled()),
                ReportMail.Cell.of(entry.actual()),
                status.equalsIgnoreCase("Normal")
                        ? ReportMail.Cell.of(status)
                        : ReportMail.Cell.alert(status));
    }
}
