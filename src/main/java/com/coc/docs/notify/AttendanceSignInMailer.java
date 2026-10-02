package com.coc.docs.notify;

import com.coc.docs.account.Account;
import com.coc.docs.account.AccountRepository;
import com.coc.docs.account.AppRole;
import com.coc.docs.auth.SignInEvent;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Mails somebody their attendance report when they sign in (ADR-089).
 *
 * <p><b>Why sign-in is the trigger.</b> The report has to reach a real inbox,
 * and the routes that did not work are instructive: the poll only mails on a
 * genuinely new arrival, so anyone who joined afterwards never got one; and
 * every per-account mailbox scheme needs a credential somebody has to supply
 * first. Signing in needs nothing configured, happens exactly when the person
 * is paying attention, and is already a point where this system knows who they
 * are. It is sent by the shared sender, the same one that delivers the
 * registration OTP — which is the part that was already proven to arrive.
 *
 * <p><b>At most one mail per person per report.</b>
 * {@link AttendanceReportSentRepository#claim} is a write, so two sign-ins
 * racing from a phone and a laptop cannot both win. Without that, signing in
 * four times in a morning would mail the same report four times, and the
 * feature would become the reason somebody filters this sender.
 *
 * <p><b>Nothing here may affect signing in.</b> {@code @Async} and
 * {@code AFTER_COMMIT}, like {@link SignInNotifier}: off the request thread so
 * nobody waits for an SMTP round trip, and after the commit so a rolled-back
 * session never produces mail. Every failure is swallowed — being unable to
 * send an attendance report is not a reason to fail a login.
 *
 * <p>// req: FR-OPS-001, NFR-SEC-PII, ADR-089
 */
@Component
public class AttendanceSignInMailer {

    private static final Logger log = LoggerFactory.getLogger(AttendanceSignInMailer.class);

    private final AccountRepository accounts;
    private final HrmsNotificationService reports;
    private final AttendanceMailService attendanceMail;
    private final AttendanceReportSentRepository alreadySent;

    public AttendanceSignInMailer(
            AccountRepository accounts,
            HrmsNotificationService reports,
            AttendanceMailService attendanceMail,
            AttendanceReportSentRepository alreadySent) {
        this.accounts = accounts;
        this.reports = reports;
        this.attendanceMail = attendanceMail;
        this.alreadySent = alreadySent;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSignIn(SignInEvent event) {
        try {
            send(event);
        } catch (RuntimeException e) {
            // Deliberately broad. This runs after somebody is already signed
            // in; there is nothing useful to do with a failure except say so.
            log.warn("attendance: could not mail a report on sign-in ({})", e.getMessage());
        }
    }

    private void send(SignInEvent event) {
        Optional<Account> account = accounts.findById(event.accountId());
        if (account.isEmpty()) {
            return;
        }
        boolean admin = account.get().role().isAtLeast(AppRole.ADMIN);
        String hrmsName = accounts.hrmsNameOf(event.accountId()).orElse(null);

        Optional<HrmsNotificationService.DatedReport> latest =
                reports.latestReportFor(event.accountId(), event.email(), hrmsName, admin);
        if (latest.isEmpty()) {
            return;
        }

        HrmsMailContent content = latest.get().content();
        if (content.rows().isEmpty()) {
            // Nothing of theirs in it. Mailing an empty table would be worse
            // than silence: it says "here is your attendance" and shows none,
            // which reads as the system being broken rather than as the person
            // not being on that department's sheet.
            return;
        }

        long notificationId = latest.get().notificationId();
        if (!alreadySent.claim(event.accountId(), notificationId)) {
            return;
        }

        if (!attendanceMail.sendReportTo(event.accountId(), event.email(), content)) {
            // Give the claim back so the next sign-in tries again. A relay
            // that was briefly down must not cost somebody that day's report.
            alreadySent.release(event.accountId(), notificationId);
            log.warn("attendance: sign-in mail to {} was not sent; it will be retried", event.email());
        }
    }
}
