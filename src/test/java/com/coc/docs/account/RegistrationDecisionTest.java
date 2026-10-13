package com.coc.docs.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.coc.docs.audit.AuditRepository;
import com.coc.docs.auth.AccountInviteMailer;
import com.coc.docs.auth.CurrentUser;
import com.coc.docs.auth.SessionRepository;
import com.coc.docs.auth.TestCallers;
import com.coc.docs.web.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Registration decisions are announced, so the applicant can be emailed, and
 * a refused applicant may apply again with the same address (ADR-096).
 * Plain unit test against a mocked repository; the SQL of the reopen itself
 * is {@code ApprovalGateTest}'s subject, against real PostgreSQL.
 *
 * <p>// req: FR-AUTH-002, ADR-017, ADR-096
 */
class RegistrationDecisionTest {

    private AccountRepository accounts;
    private final List<Object> published = new ArrayList<>();
    private AccountService service;
    private final CurrentUser admin = TestCallers.user(AppRole.SUPER_ADMIN);

    @BeforeEach
    void setUp() {
        accounts = mock(AccountRepository.class);
        service = new AccountService(
                accounts,
                mock(SessionRepository.class),
                mock(AuditRepository.class),
                new ObjectMapper(),
                mock(PasswordEncoder.class),
                mock(AccountInviteMailer.class),
                published::add);
    }

    private static Account applicant(AccountStatus status) {
        return new Account(
                UUID.randomUUID(),
                "new.person@example.com",
                "New Person",
                EnumSet.of(AppRole.GUEST),
                null,
                null,
                status,
                null,
                null,
                Instant.now(),
                Instant.now(),
                false,
                true);
    }

    @Test
    @DisplayName("approving announces it, with the role granted, for the approval email")
    void approvingAnnouncesTheDecision() {
        Account pending = applicant(AccountStatus.PENDING);
        Account active = pending.withStatus(AccountStatus.ACTIVE);
        when(accounts.findById(pending.id()))
                .thenReturn(
                        Optional.of(pending),
                        Optional.of(active
                        Optional.of(active
                        Optional.of(active.withRoles(EnumSet.of(AppRole.REVIEWER))));

        service.approve(pending.id(), AppRole.REVIEWER, admin);

        assertThat(published).containsExactly(new RegistrationDecidedEvent(
                pending.id(), "new.person@example.com", "New Person", true, AppRole.REVIEWER));
    }

    @Test
    @DisplayName("refusing announces it, for the not-approved email")
    void refusingAnnouncesTheDecision() {
        Account pending = applicant(AccountStatus.PENDING);
        when(accounts.findById(pending.id()))
                .thenReturn(Optional.of(pending), Optional.of(pending.withStatus(AccountStatus.REJECTED)));

        service.reject(pending.id(), admin);

        assertThat(published).containsExactly(new RegistrationDecidedEvent(
                pending.id(), "new.person@example.com", "New Person", false, null));
    }

    @Test
    @DisplayName("a decision that is itself refused (not pending) announces nothing, so nobody is emailed")
    void noDecisionNoAnnouncement() {
        Account active = applicant(AccountStatus.ACTIVE);
        when(accounts.findById(active.id())).thenReturn(Optional.of(active));

        assertThatThrownBy(() -> service.reject(active.id(), admin)).isInstanceOf(ApiException.class);

        assertThat(published).isEmpty();
    }

    @Test
    @DisplayName("registering again after a refusal reopens the same account instead of a duplicate-email error")
    void aRefusedApplicantMayRegisterAgain() {
        Account rejected = applicant(AccountStatus.REJECTED);
        Account reopened = rejected.withStatus(AccountStatus.PENDING);
        when(accounts.findByEmail("new.person@example.com")).thenReturn(Optional.of(rejected));
        when(accounts.reopenApplication(rejected.id(), "New Name", "hash")).thenReturn(Optional.of(reopened));

        Account result = service.register("new.person@example.com", "New Name", "hash");

        assertThat(result.id()).isEqualTo(rejected.id());
        assertThat(result.status()).isEqualTo(AccountStatus.PENDING);
        verify(accounts, never()).insertApplication(any(), any(), any(), any());
    }

    @Test
    @DisplayName("an address held by any account that was not refused is still a 409")
    void anyOtherAccountStillOwnsTheAddress() {
        for (AccountStatus status : List.of(AccountStatus.PENDING, AccountStatus.ACTIVE)) {
            when(accounts.findByEmail("new.person@example.com")).thenReturn(Optional.of(applicant(status)));

            assertThatThrownBy(() -> service.register("new.person@example.com", "New Name", "hash"))
                    .isInstanceOfSatisfying(ApiException.class, e -> {
                        assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                        assertThat(e.getMessage()).isEqualTo("that email address is already registered");
                    });
        }
        verify(accounts, never()).reopenApplication(any(), any(), any());
    }
}
