package com.coc.docs.notify;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.coc.docs.account.Account;
import com.coc.docs.account.AccountRepository;
import com.coc.docs.account.AppRole;
import com.coc.docs.auth.SignInEvent;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Mailing the attendance report on sign-in (ADR-089).
 *
 * <p>Most of these assert that a mail is <em>not</em> sent. That is the shape
 * of the risk: signing in is frequent, so the ways this goes wrong are all
 * "sent again" or "sent when there was nothing to say", and both teach people
 * to ignore the sender.
 *
 * <p>// req: FR-OPS-001, ADR-089
 */
class AttendanceSignInMailerTest {

    private static final UUID ANA = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-10-02T01:00:00Z");

    private final AccountRepository accounts = mock(AccountRepository.class);
    private final HrmsNotificationService reports = mock(HrmsNotificationService.class);
    private final AttendanceMailService attendanceMail = mock(AttendanceMailService.class);
    private final AttendanceReportSentRepository alreadySent = mock(AttendanceReportSentRepository.class);

    private final AttendanceSignInMailer mailer =
            new AttendanceSignInMailer(accounts, reports, attendanceMail, alreadySent);

    private static SignInEvent signIn() {
        return new SignInEvent(ANA, "ana@example.com", NOW);
    }

    private static Account account(AppRole role) {
        return new Account(
                ANA,
                "ana@example.com",
                "Ana",
                java.util.EnumSet.of(role),
                null,
                null,
                com.coc.docs.account.AccountStatus.ACTIVE,
                null,
                null,
                NOW,
                NOW,
                false,
                true);
    }

    private static HrmsMailContent reportWithRows() {
        return new HrmsMailContent(
                "Attendance on 08-Sep-2026",
                java.time.LocalDate.of(2026, 9, 8),
                "COOCON Department",
                List.of(new HrmsMailContent.Row("Ana", null, null)),
                List.of(),
                null);
    }

    private static HrmsMailContent emptyReport() {
        return new HrmsMailContent(
                "Attendance on 08-Sep-2026",
                java.time.LocalDate.of(2026, 9, 8),
                "COOCON Department",
                List.of(),
                List.of(),
                null);
    }

    private void given(HrmsMailContent content) {
        when(accounts.findById(ANA)).thenReturn(Optional.of(account(AppRole.USER)));
        when(accounts.hrmsNameOf(ANA)).thenReturn(Optional.empty());
        when(reports.latestReportFor(eq(ANA), any(), any(), anyBoolean()))
                .thenReturn(Optional.of(new HrmsNotificationService.DatedReport(9L, content)));
    }

    @Test
    @DisplayName("mails the report the first time somebody signs in")
    void sendsOnFirstSignIn() {
        given(reportWithRows());
        when(alreadySent.claim(ANA, 9L)).thenReturn(true);
        when(attendanceMail.sendReportTo(eq(ANA), eq("ana@example.com"), any())).thenReturn(true);

        mailer.onSignIn(signIn());

        verify(attendanceMail).sendReportTo(eq(ANA), eq("ana@example.com"), any());
    }

    /**
     * The whole reason V63 exists. People sign in from a phone and a laptop,
     * and sessions expire; without the claim this mails the same report every
     * time.
     */
    @Test
    @DisplayName("does not send the same report twice")
    void doesNotResend() {
        given(reportWithRows());
        when(alreadySent.claim(ANA, 9L)).thenReturn(false);

        mailer.onSignIn(signIn());

        verify(attendanceMail, never()).sendReportTo(any(), any(), any());
    }

    /**
     * An empty table under "here is your attendance" reads as a broken system
     * rather than as not being on that department's sheet.
     */
    @Test
    @DisplayName("sends nothing when the report has no rows for this person")
    void noRowsNoMail() {
        given(emptyReport());

        mailer.onSignIn(signIn());

        verify(attendanceMail, never()).sendReportTo(any(), any(), any());
        verify(alreadySent, never()).claim(any(), anyLong());
    }

    @Test
    @DisplayName("sends nothing when there is no report to send")
    void noReportNoMail() {
        when(accounts.findById(ANA)).thenReturn(Optional.of(account(AppRole.USER)));
        when(accounts.hrmsNameOf(ANA)).thenReturn(Optional.empty());
        when(reports.latestReportFor(eq(ANA), any(), any(), anyBoolean())).thenReturn(Optional.empty());

        mailer.onSignIn(signIn());

        verify(attendanceMail, never()).sendReportTo(any(), any(), any());
    }

    /**
     * A relay that was briefly down must not cost somebody that day's report
     * permanently — the claim goes back so the next sign-in retries.
     */
    @Test
    @DisplayName("gives the claim back when the send fails")
    void releasesOnFailure() {
        given(reportWithRows());
        when(alreadySent.claim(ANA, 9L)).thenReturn(true);
        when(attendanceMail.sendReportTo(eq(ANA), eq("ana@example.com"), any())).thenReturn(false);

        mailer.onSignIn(signIn());

        verify(alreadySent).release(ANA, 9L);
    }

    /** An admin is scoped differently, and the mailer must say which it is. */
    @Test
    @DisplayName("asks for the whole report for an admin and only their rows for a user")
    void scopesByRole() {
        when(accounts.findById(ANA)).thenReturn(Optional.of(account(AppRole.ADMIN)));
        when(accounts.hrmsNameOf(ANA)).thenReturn(Optional.empty());
        when(reports.latestReportFor(eq(ANA), any(), any(), eq(true))).thenReturn(Optional.empty());

        mailer.onSignIn(signIn());

        verify(reports).latestReportFor(eq(ANA), eq("ana@example.com"), any(), eq(true));
    }

    /**
     * Signing in must not be affected by any of this. The listener runs after
     * the session is already issued, so a failure has nowhere useful to go.
     */
    @Test
    @DisplayName("swallows a failure rather than letting it escape the listener")
    void neverThrows() {
        when(accounts.findById(ANA)).thenThrow(new IllegalStateException("database gone"));

        mailer.onSignIn(signIn());
    }
}
