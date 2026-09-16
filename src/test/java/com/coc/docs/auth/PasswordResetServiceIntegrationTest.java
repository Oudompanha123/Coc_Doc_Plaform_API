package com.coc.docs.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.coc.docs.PostgresTestBase;
import com.coc.docs.account.Account;
import com.coc.docs.account.AccountRepository;
import com.coc.docs.account.AccountStatus;
import com.coc.docs.account.AppRole;
import com.coc.docs.web.ApiException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * The reset flow against a real PostgreSQL (ADR-018, revised by ADR-029).
 * {@link PasswordResetMailer} is mocked deliberately — what is under test is
 * everything around it: that every existing account is eligible (including
 * a Google-only one, getting its first password — ADR-029), that a redeemed
 * token cannot be redeemed twice, and that a reset revokes live sessions —
 * not SMTP delivery, which has no place in this suite.
 *
 * <p>// req: FR-AUTH-002, FR-AUTH-007, ADR-014, ADR-018, ADR-029
 */
class PasswordResetServiceIntegrationTest extends PostgresTestBase {

    @Autowired PasswordResetService resets;
    @Autowired AccountRepository accounts;
    @Autowired SessionRepository sessions;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JdbcClient jdbc;

    @MockitoBean PasswordResetMailer mailer;

    private Account withPassword;

    @BeforeEach
    void setUp() {
        jdbc.sql("truncate audit_log, app_session, password_reset_token, page_version, "
                        + "page_slug_history, page, section, account restart identity cascade")
                .update();

        // account carries a deferred constraint trigger (V4): every UPDATE
        // re-asserts that an active super_admin still exists, and it fires at
        // COMMIT rather than at the statement. Resetting a password updates
        // account.password_hash, so without this row the commit fails with
        // "at least one active super_admin must remain" — a failure that
        // surfaces as "JDBC commit failed" and points at the transaction
        // rather than at the missing fixture.
        //
        // Production can never be in that state: the bootstrap makes the very
        // first account a super_admin. Only a truncated test schema can.
        accounts.insert("boss@example.com", "Boss", AppRole.SUPER_ADMIN, null, AccountStatus.ACTIVE);

        withPassword = accounts.insertApplication(
                "reset.me@example.com", "Reset Me", null, passwordEncoder.encode("OldPassword123!"));
    }

    /** Captures the raw token {@code requestReset} mints, the only way to get it back for a test. */
    private String mintedToken(String email) {
        ArgumentCaptor<String> token = ArgumentCaptor.forClass(String.class);
        resets.requestReset(email);
        // The link goes to the address of record, not to whatever casing the
        // requester typed. Asserting the input back would have pinned the
        // opposite behaviour — mail addressed to an unnormalised string.
        // true: withPassword already holds a password_hash, so this mints a
        // genuine reset, not a first-password "set" (ADR-029).
        verify(mailer).sendResetLink(eq(withPassword.email()), token.capture(), eq(true));
        return token.getValue();
    }

    /**
     * Back-dates a token out of its window.
     *
     * <p>Both columns move. Moving {@code expires_at} alone lands it before
     * {@code issued_at} and trips the table's own check constraint, so the row
     * is rejected before any expiry logic is reached — the test then fails on
     * a write it only meant to set up.
     */
    private void expire(UUID accountId) {
        jdbc.sql("""
                        update password_reset_token
                           set issued_at  = now() - interval '2 hours',
                               expires_at = now() - interval '90 minutes'
                         where account_id = ?
                        """)
                .param(accountId)
                .update();
    }

    @Test
    @DisplayName("mints and emails a token for an account that already has a password")
    void mintsATokenForAnEligibleAccount() {
        String token = mintedToken("reset.me@example.com");
        assertThat(token).isNotBlank();

        Long rowCount = jdbc.sql("select count(*) from password_reset_token where account_id = ?")
                .param(withPassword.id())
                .query(Long.class)
                .single();
        assertThat(rowCount).isEqualTo(1L);
    }

    @Test
    @DisplayName("rejects an address with no account at all (ADR-019)")
    void rejectsAnUnknownAddress() {
        assertThatThrownBy(() -> resets.requestReset("nobody@example.com"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(e.getMessage()).isEqualTo("No account found with this email address.");
                });
        verify(mailer, never()).sendResetLink(any(), any(), anyBoolean());
    }

    @Test
    @DisplayName("mints a token and emails a Google-only account too, as a first password (ADR-029)")
    void mintsATokenForAGoogleOnlyAccount() {
        Account googleOnly =
                accounts.insert("google.only@example.com", "Google Only", AppRole.USER, null, AccountStatus.ACTIVE);
        ArgumentCaptor<String> token = ArgumentCaptor.forClass(String.class);

        resets.requestReset("google.only@example.com");

        // false: this account holds no password_hash yet, so the emailed
        // wording is "set", not "reset" — ADR-029 revises ADR-018 decision 2,
        // which used to leave this case untouched entirely.
        verify(mailer).sendResetLink(eq("google.only@example.com"), token.capture(), eq(false));
        Long rowCount = jdbc.sql("select count(*) from password_reset_token where account_id = ?")
                .param(googleOnly.id())
                .query(Long.class)
                .single();
        assertThat(rowCount).isEqualTo(1L);
    }

    @Test
    @DisplayName("redeeming a Google-only account's token sets its first password and says so distinctly (ADR-029)")
    void redeemingSetsAnInitialPasswordForAGoogleOnlyAccount() {
        accounts.insert("google.only2@example.com", "Google Only", AppRole.USER, null, AccountStatus.ACTIVE);
        ArgumentCaptor<String> token = ArgumentCaptor.forClass(String.class);
        resets.requestReset("google.only2@example.com");
        verify(mailer).sendResetLink(eq("google.only2@example.com"), token.capture(), eq(false));

        String message = resets.resetPassword(token.getValue(), "NewPassword456!");

        assertThat(message).isEqualTo("Your password has been set. You can now log in with your email and password.");
        String newHash = accounts
                .findCredentialByEmailIgnoreCase("google.only2@example.com")
                .orElseThrow()
                .passwordHash();
        assertThat(passwordEncoder.matches("NewPassword456!", newHash)).isTrue();
    }

    @Test
    @DisplayName("looks the address up case-insensitively, same as sign-in")
    void isCaseInsensitive() {
        String token = mintedToken("RESET.ME@EXAMPLE.COM");
        assertThat(token).isNotBlank();
    }

    @Test
    @DisplayName("a redeemed token sets the new password and cannot be redeemed twice")
    void resetIsAppliedOnceAndOnlyOnce() {
        String token = mintedToken("reset.me@example.com");

        String message = resets.resetPassword(token, "NewPassword456!");

        // "reset", not "set" (ADR-029) -- withPassword already held a password.
        assertThat(message).isEqualTo("Your password has been reset. You can now log in.");
        String newHash = accounts
                .findCredentialByEmailIgnoreCase("reset.me@example.com")
                .orElseThrow()
                .passwordHash();
        assertThat(passwordEncoder.matches("NewPassword456!", newHash)).isTrue();
        assertThat(passwordEncoder.matches("OldPassword123!", newHash)).isFalse();

        assertThatThrownBy(() -> resets.resetPassword(token, "AnotherPassword789!"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    @DisplayName("revokes every live session for the account (FR-AUTH-007)")
    void revokesLiveSessions() {
        String token = mintedToken("reset.me@example.com");

        byte[] tokenHash = {1, 2, 3, 4};
        sessions.insert(tokenHash, withPassword.id(), Instant.now().plusSeconds(3600), "test-agent", "127.0.0.1");
        assertThat(sessions.find(tokenHash).orElseThrow().isLive(Instant.now())).isTrue();

        resets.resetPassword(token, "NewPassword456!");

        assertThat(sessions.find(tokenHash).orElseThrow().isLive(Instant.now())).isFalse();
    }

    @Test
    @DisplayName("rejects a token that was never issued")
    void rejectsAnUnknownToken() {
        assertThatThrownBy(() -> resets.resetPassword("not-a-real-token", "NewPassword456!"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    @DisplayName("rejects a token past its 30-minute window")
    void rejectsAnExpiredToken() {
        String token = mintedToken("reset.me@example.com");

        // Back-date the row directly -- waiting out a real 30-minute TTL has
        // no place in a test suite.
        expire(withPassword.id());

        assertThatThrownBy(() -> resets.resetPassword(token, "NewPassword456!"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    @DisplayName("purgeExpired removes only rows past their expiry")
    void purgeExpiredRemovesOnlyExpiredRows() {
        mintedToken("reset.me@example.com");
        expire(withPassword.id());

        int purged = resets.purgeExpired();

        assertThat(purged).isEqualTo(1);
        Long remaining = jdbc.sql("select count(*) from password_reset_token where account_id = ?")
                .param(withPassword.id())
                .query(Long.class)
                .single();
        assertThat(remaining).isZero();
    }
}
