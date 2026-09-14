package com.coc.docs.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
 * The reset flow against a real PostgreSQL (ADR-017). {@link
 * PasswordResetMailer} is mocked deliberately — what is under test is
 * everything around it: which accounts are even eligible, that a redeemed
 * token cannot be redeemed twice, and that a reset revokes live sessions —
 * not SMTP delivery, which has no place in this suite.
 *
 * <p>// req: FR-AUTH-002, FR-AUTH-007, ADR-014, ADR-017
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

        withPassword = accounts.insertApplication(
                "reset.me@example.com", "Reset Me", null, passwordEncoder.encode("OldPassword123!"));
    }

    /** Captures the raw token {@code requestReset} mints, the only way to get it back for a test. */
    private String mintedToken(String email) {
        ArgumentCaptor<String> token = ArgumentCaptor.forClass(String.class);
        resets.requestReset(email);
        verify(mailer).sendResetLink(eq(email), token.capture());
        return token.getValue();
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
    @DisplayName("does nothing for an address with no account at all")
    void ignoresAnUnknownAddress() {
        resets.requestReset("nobody@example.com");
        verify(mailer, never()).sendResetLink(any(), any());
    }

    @Test
    @DisplayName("does nothing for a Google-only account (ADR-017 decision 2)")
    void ignoresAGoogleOnlyAccount() {
        accounts.insert("google.only@example.com", "Google Only", AppRole.USER, null, AccountStatus.ACTIVE);

        resets.requestReset("google.only@example.com");
        verify(mailer, never()).sendResetLink(any(), any());
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

        resets.resetPassword(token, "NewPassword456!");

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
        jdbc.sql("update password_reset_token set expires_at = ? where account_id = ?")
                .params(OffsetDateTime.ofInstant(Instant.now().minusSeconds(60), ZoneOffset.UTC), withPassword.id())
                .update();

        assertThatThrownBy(() -> resets.resetPassword(token, "NewPassword456!"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    @DisplayName("purgeExpired removes only rows past their expiry")
    void purgeExpiredRemovesOnlyExpiredRows() {
        mintedToken("reset.me@example.com");
        jdbc.sql("update password_reset_token set expires_at = ? where account_id = ?")
                .params(OffsetDateTime.ofInstant(Instant.now().minusSeconds(60), ZoneOffset.UTC), withPassword.id())
                .update();

        int purged = resets.purgeExpired();

        assertThat(purged).isEqualTo(1);
        Long remaining = jdbc.sql("select count(*) from password_reset_token where account_id = ?")
                .param(withPassword.id())
                .query(Long.class)
                .single();
        assertThat(remaining).isZero();
    }
}
