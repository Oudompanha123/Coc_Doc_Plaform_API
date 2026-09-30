package com.coc.docs.notify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.coc.docs.account.AccountRepository;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Who receives whose hours.
 *
 * <p>The in-app view leaks to one person at a time; a mail leaks to an inbox
 * that person keeps. So these assert the same property the screen's tests do,
 * against the send path: one address per message, and never a colleague's row
 * (NFR-SEC-PII).
 *
 * <p>// req: FR-OPS-001, NFR-SEC-PII, ADR-057
 */
class AttendanceMailServiceTest {

    private final AccountRepository accounts = mock(AccountRepository.class);
    private final NotificationMailService mailService = mock(NotificationMailService.class);
    private final NotificationProperties properties =
            new NotificationProperties("Coc Docs Platform", "Asia/Phnom_Penh", List.of(), false);
    private final ReportMailRenderer renderer = new ReportMailRenderer(properties);

    private final AccountMailCredentialRepository credentials = mock(AccountMailCredentialRepository.class);
    private final PersonalMailSenderFactory personalSenders = mock(PersonalMailSenderFactory.class);

    private final AttendanceMailService service = new AttendanceMailService(
            accounts, mailService, renderer, properties, credentials, personalSenders);

    private static HrmsMailContent.Entry entry(String scheduled, String actual, String status) {
        return new HrmsMailContent.Entry(scheduled, actual, status);
    }

    private static HrmsMailContent report(String... names) {
        List<HrmsMailContent.Row> rows = java.util.Arrays.stream(names)
                .map(n -> new HrmsMailContent.Row(
                        n, entry("17:00", "17:06", "Normal"), entry("08:00", "08:09", "Late Check-In")))
                .toList();
        return new HrmsMailContent(
                "Attendance on 28-Aug-2026", LocalDate.of(2026, 8, 28), "COOCON Department",
                rows, List.of(), "do not reply");
    }

    private static AccountRepository.MailTarget target(String email) {
        return new AccountRepository.MailTarget(java.util.UUID.randomUUID(), email, null);
    }

    @Test
    @DisplayName("each matched person gets one mail, addressed only to them")
    void oneMailPerPersonAddressedOnlyToThem() {
        when(accounts.activeMailTargets())
                .thenReturn(List.of(target("samoeunveth99@gmail.com"), target("kenvantha@gmail.com")));
        when(mailService.send(any(), anyString(), anyString(), anyString())).thenReturn(true);

        assertThat(service.sendPersonalReports(report("Veth Sam Oeun", "Ken Vantha"))).isEqualTo(2);

        ArgumentCaptor<List<String>> to = ArgumentCaptor.captor();
        verify(mailService, org.mockito.Mockito.times(2))
                .send(to.capture(), anyString(), anyString(), anyString());
        assertThat(to.getAllValues()).allSatisfy(list -> assertThat(list).hasSize(1));
        assertThat(to.getAllValues().stream().flatMap(List::stream))
                .containsExactlyInAnyOrder("samoeunveth99@gmail.com", "kenvantha@gmail.com");
    }

    @Test
    @DisplayName("the body carries only the recipient's own row, never a colleague's")
    void bodyCarriesOnlyTheirOwnRow() {
        when(accounts.activeMailTargets()).thenReturn(List.of(target("samoeunveth99@gmail.com")));
        when(mailService.send(any(), anyString(), anyString(), anyString())).thenReturn(true);

        service.sendPersonalReports(report("Veth Sam Oeun", "Ken Vantha"));

        ArgumentCaptor<String> html = ArgumentCaptor.captor();
        verify(mailService).send(any(), anyString(), anyString(), html.capture());
        assertThat(html.getValue()).contains("Veth Sam Oeun");
        assertThat(html.getValue()).doesNotContain("Ken Vantha");
    }

    @Test
    @DisplayName("somebody with no row in the report is sent nothing -- silence, not a daily 'you were not in it'")
    void unmatchedAccountIsSentNothing() {
        when(accounts.activeMailTargets()).thenReturn(List.of(target("oudompanha456@gmail.com")));

        assertThat(service.sendPersonalReports(report("Veth Sam Oeun"))).isZero();
        verify(mailService, never()).send(any(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("account.hrms_name still overrides the address")
    void explicitLinkOverridesTheAddress() {
        when(accounts.activeMailTargets())
                .thenReturn(List.of(new AccountRepository.MailTarget(java.util.UUID.randomUUID(), "someone@example.com", "Ken Vantha")));
        when(mailService.send(any(), anyString(), anyString(), anyString())).thenReturn(true);

        assertThat(service.sendPersonalReports(report("Veth Sam Oeun", "Ken Vantha"))).isEqualTo(1);

        ArgumentCaptor<String> html = ArgumentCaptor.captor();
        verify(mailService).send(any(), anyString(), anyString(), html.capture());
        assertThat(html.getValue()).contains("Ken Vantha");
        assertThat(html.getValue()).doesNotContain("Veth Sam Oeun");
    }

    @Test
    @DisplayName("a report with no rows -- a leave request, or one the parser lost -- mails nobody")
    void reportWithNoRowsMailsNobody() {
        HrmsMailContent leave = new HrmsMailContent(
                "[KOSIGN] Your leave request has been rejected", null, "Leave Request",
                List.of(), List.of(new HrmsMailContent.Detail("Type", "Annual leave")), "");

        assertThat(service.sendPersonalReports(leave)).isZero();
        verify(accounts, never()).activeMailTargets();
        verify(mailService, never()).send(any(), anyString(), anyString(), anyString());
    }
}
