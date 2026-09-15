package com.coc.docs.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.coc.docs.PostgresTestBase;
import com.coc.docs.account.Account;
import com.coc.docs.account.AccountRepository;
import com.coc.docs.account.AccountStatus;
import com.coc.docs.account.AppRole;
import com.coc.docs.web.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * The registration OTP flow against a real PostgreSQL (ADR-021). {@link
 * RegistrationOtpMailer} is mocked deliberately, same reasoning as {@link
 * PasswordResetServiceIntegrationTest}: what is under test is everything
 * around it — the challenge lifecycle, the attempt cap, the cooldown, and
 * that a confirmed code actually produces the {@code pending}/{@code guest}
 * account ADR-017 specifies — not SMTP delivery.
 *
 * <p>// req: FR-AUTH-002, ADR-014, ADR-017, ADR-021
 */
class RegistrationOtpServiceIntegrationTest extends PostgresTestBase {

    @Autowired RegistrationOtpService registrations;
    @Autowired AccountRepository accounts;
    @Autowired JdbcClient jdbc;

    @MockitoBean RegistrationOtpMailer mailer;

    @BeforeEach
    void setUp() {
        jdbc.sql("truncate audit_log, app_session, registration_challenge, password_reset_token, "
                        + "page_version, page_slug_history, page, section, account restart identity cascade")
                .update();

        // account carries a deferred constraint trigger (V4): a bootstrap
        // super_admin keeps every commit in this suite from tripping the
        // "at least one active super_admin must remain" guard, the same
        // fixture PasswordResetServiceIntegrationTest sets up and for the
        // same reason.
        accounts.insert("boss@example.com", "Boss", AppRole.SUPER_ADMIN, null, AccountStatus.ACTIVE);
    }

    /** Captures the raw code {@code start} mints, the only way to get it back for a test. */
    private String mintedCode(String email, String displayName) {
        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        registrations.start(email, displayName, "bcrypt-hash");
        verify(mailer).sendCode(eq(email), code.capture(), any());
        return code.getValue();
    }

    private void expire(String email) {
        jdbc.sql("""
                        update registration_challenge
                           set issued_at  = now() - interval '2 hours',
                               expires_at = now() - interval '90 minutes'
                         where lower(email) = lower(?)
                        """)
                .param(email)
                .update();
    }

    @Test
    @DisplayName("mints and emails a 6-digit code, and no account exists yet")
    void mintsACodeWithoutCreatingAnAccount() {
        String code = mintedCode("new.person@example.com", "New Person");

        assertThat(code).matches("\\d{6}");
        assertThat(accounts.findByEmail("new.person@example.com")).isEmpty();

        Long rowCount = jdbc.sql("select count(*) from registration_challenge where lower(email) = lower(?)")
                .param("new.person@example.com")
                .query(Long.class)
                .single();
        assertThat(rowCount).isEqualTo(1L);
    }

    @Test
    @DisplayName("refuses to start for an email that already has an account")
    void refusesAnAlreadyRegisteredEmail() {
        accounts.insertApplication("existing@example.com", "Existing", null, "bcrypt-hash");

        assertThatThrownBy(() -> registrations.start("existing@example.com", "Existing", "bcrypt-hash"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.CONFLICT));
        verify(mailer, never()).sendCode(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("verify creates the pending, guest account exactly like the old single-step register")
    void verifyCreatesThePendingApplication() {
        String code = mintedCode("new.person@example.com", "New Person");

        Account created = registrations.verify("new.person@example.com", code);

        assertThat(created.status()).isEqualTo(AccountStatus.PENDING);
        assertThat(created.roles()).containsExactly(AppRole.GUEST);
        assertThat(accounts.findByEmail("new.person@example.com")).isPresent();
    }

    @Test
    @DisplayName("a confirmed code cannot be presented twice")
    void codeIsSpentOnSuccess() {
        String code = mintedCode("new.person@example.com", "New Person");
        registrations.verify("new.person@example.com", code);

        assertThatThrownBy(() -> registrations.verify("new.person@example.com", code))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    @DisplayName("rejects a code past its 10-minute window, distinctly from a wrong one")
    void rejectsAnExpiredCode() {
        String code = mintedCode("new.person@example.com", "New Person");
        expire("new.person@example.com");

        assertThatThrownBy(() -> registrations.verify("new.person@example.com", code))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("expired");
    }

    @Test
    @DisplayName("rejects a wrong code and counts the attempt")
    void rejectsAWrongCode() {
        mintedCode("new.person@example.com", "New Person");

        assertThatThrownBy(() -> registrations.verify("new.person@example.com", "000000"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("incorrect");

        Integer attempts = jdbc.sql("select attempts from registration_challenge where lower(email) = lower(?)")
                .param("new.person@example.com")
                .query(Integer.class)
                .single();
        assertThat(attempts).isEqualTo(1);
    }

    @Test
    @DisplayName("locks out after 5 wrong attempts, even with time left on the clock")
    void locksOutAfterMaxAttempts() {
        mintedCode("new.person@example.com", "New Person");

        for (int i = 0; i < RegistrationOtpService.MAX_ATTEMPTS; i++) {
            assertThatThrownBy(() -> registrations.verify("new.person@example.com", "000000"))
                    .isInstanceOf(ApiException.class);
        }

        assertThatThrownBy(() -> registrations.verify("new.person@example.com", "000000"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Too many incorrect attempts");
    }

    @Test
    @DisplayName("resend mints a fresh code and resets the attempt count")
    void resendIssuesAFreshCode() {
        String firstCode = mintedCode("new.person@example.com", "New Person");
        // Spend a wrong guess so attempts > 0 before resending.
        assertThatThrownBy(() -> registrations.verify("new.person@example.com", "000000"))
                .isInstanceOf(ApiException.class);

        // The cooldown would otherwise refuse an immediate resend — back-date
        // issued_at rather than waiting out a real 60 seconds in a test.
        jdbc.sql("update registration_challenge set issued_at = now() - interval '2 minutes' "
                        + "where lower(email) = lower(?)")
                .param("new.person@example.com")
                .update();

        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        registrations.resend("new.person@example.com");
        verify(mailer).sendCode(eq("new.person@example.com"), code.capture(), any());
        String secondCode = code.getValue();

        assertThat(secondCode).isNotEqualTo(firstCode);
        // The old code no longer works; a fresh challenge replaced it outright.
        assertThatThrownBy(() -> registrations.verify("new.person@example.com", firstCode))
                .isInstanceOf(ApiException.class);

        Account created = registrations.verify("new.person@example.com", secondCode);
        assertThat(created.status()).isEqualTo(AccountStatus.PENDING);
    }

    @Test
    @DisplayName("refuses a resend inside the 60-second cooldown")
    void refusesAResendTooSoon() {
        mintedCode("new.person@example.com", "New Person");

        assertThatThrownBy(() -> registrations.resend("new.person@example.com"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
    }

    @Test
    @DisplayName("resend refuses an email with no registration pending")
    void resendRefusesAnUnknownEmail() {
        assertThatThrownBy(() -> registrations.resend("nobody@example.com"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    @DisplayName("purgeExpired removes only rows past their expiry")
    void purgeExpiredRemovesOnlyExpiredRows() {
        mintedCode("new.person@example.com", "New Person");
        expire("new.person@example.com");

        int purged = registrations.purgeExpired();

        assertThat(purged).isEqualTo(1);
        Long remaining = jdbc.sql("select count(*) from registration_challenge where lower(email) = lower(?)")
                .param("new.person@example.com")
                .query(Long.class)
                .single();
        assertThat(remaining).isZero();
    }

    @Test
    @DisplayName("resubmitting the form for the same email replaces the challenge, subject to the same cooldown")
    void resubmittingIsTreatedLikeAResend() {
        mintedCode("new.person@example.com", "New Person");

        assertThatThrownBy(() -> registrations.start("new.person@example.com", "New Person", "bcrypt-hash"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
    }
}
