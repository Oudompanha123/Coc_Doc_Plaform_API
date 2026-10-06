package com.coc.docs.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.coc.docs.account.Account;
import com.coc.docs.account.AccountCredential;
import com.coc.docs.account.AccountRepository;
import com.coc.docs.account.AccountStatus;
import com.coc.docs.account.AppRole;
import com.coc.docs.audit.AuditRepository;
import com.coc.docs.config.AppProperties;
import com.coc.docs.web.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

/**
 * Password sign-in on a registration still awaiting approval (ADR-095).
 *
 * <p>With the right password, the applicant is told their registration is
 * waiting — the same 403 Google sign-in gives. Without it, every refusal stays
 * the one uniform 401, so nobody can learn who has applied. Plain unit test:
 * no database, no Docker.
 *
 * <p>// req: FR-AUTH-002, ADR-014, ADR-095
 */
class PasswordSignInPendingTest {

    private static final String EMAIL = "applicant@example.com";
    private static final String RIGHT = "Right-Password-1";
    private static final String HASH = "{hash}";

    private AccountRepository accounts;
    private AuditRepository audit;
    private SessionRepository sessionRepository;
    private SessionService sessions;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        accounts = mock(AccountRepository.class);
        audit = mock(AuditRepository.class);
        sessionRepository = mock(SessionRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        when(encoder.matches(anyString(), anyString())).thenAnswer(inv -> RIGHT.equals(inv.getArgument(0)) && HASH.equals(inv.getArgument(1)));
        sessions = new SessionService(
                sessionRepository,
                accounts,
                audit,
                mock(GoogleIdTokenVerifier.class),
                mock(ObjectProvider.class),
                mock(ObjectProvider.class),
                new AppProperties(Duration.ofDays(1), "https://docs.example.com", true, "", "", new AppProperties.Storage("", "", "", "", "")),
                new ObjectMapper(),
                Clock.systemUTC(),
                encoder,
                mock(AccountTotpRepository.class),
                mock(MfaLoginChallengeRepository.class),
                event -> {});
    }

    private void accountWithStatus(AccountStatus status) {
        Account account = TestCallers.user(AppRole.GUEST).account().withStatus(status);
        when(accounts.findCredentialByEmailIgnoreCase(EMAIL)).thenReturn(Optional.of(new AccountCredential(account, HASH)));
    }

    private String auditedReason() {
        ArgumentCaptor<JsonNode> detail = ArgumentCaptor.forClass(JsonNode.class);
        verify(audit).append(any(), any(), any(), any(), any(), detail.capture());
        return detail.getValue().path("reason").asText();
    }

    @Test
    void the_right_password_on_a_pending_registration_says_it_is_awaiting_approval() {
        accountWithStatus(AccountStatus.PENDING);

        assertThatThrownBy(() -> sessions.signInWithPassword(EMAIL, RIGHT, "ua", "127.0.0.1"))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(e.getMessage()).isEqualTo("this registration is awaiting approval");
                });
        assertThat(auditedReason()).isEqualTo("password_sign_in_pending_approval");
    }

    @Test
    void the_wrong_password_on_a_pending_registration_gets_the_uniform_refusal() {
        accountWithStatus(AccountStatus.PENDING);

        assertThatThrownBy(() -> sessions.signInWithPassword(EMAIL, "wrong", "ua", "127.0.0.1"))
                .isInstanceOf(GoogleIdTokenVerifier.AuthenticationFailure.class);
        assertThat(auditedReason()).isEqualTo("password_sign_in_refused");
    }

    @Test
    void an_unknown_address_gets_the_uniform_refusal() {
        when(accounts.findCredentialByEmailIgnoreCase(EMAIL)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> sessions.signInWithPassword(EMAIL, RIGHT, "ua", "127.0.0.1"))
                .isInstanceOf(GoogleIdTokenVerifier.AuthenticationFailure.class);
    }

    @Test
    void a_rejected_registration_gets_the_uniform_refusal_even_with_the_right_password() {
        accountWithStatus(AccountStatus.REJECTED);

        assertThatThrownBy(() -> sessions.signInWithPassword(EMAIL, RIGHT, "ua", "127.0.0.1"))
                .isInstanceOf(GoogleIdTokenVerifier.AuthenticationFailure.class);
    }

    @Test
    void the_refusal_s_audit_entry_survives_the_403() throws NoSuchMethodException {
        // An ApiException rolls the transaction back unless told not to; the
        // record of the refused attempt must stay, as for the 401.
        Transactional tx = SessionService.class
                .getMethod("signInWithPassword", String.class, String.class, String.class, String.class)
                .getAnnotation(Transactional.class);

        assertThat(tx.noRollbackFor()).contains(ApiException.class, GoogleIdTokenVerifier.AuthenticationFailure.class);
    }
}
