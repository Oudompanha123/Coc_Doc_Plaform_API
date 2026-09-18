package com.coc.docs.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.coc.docs.PostgresTestBase;
import com.coc.docs.account.Account;
import com.coc.docs.account.AccountRepository;
import com.coc.docs.account.AccountStatus;
import com.coc.docs.account.AppRole;
import com.coc.docs.account.TestPermissions;
import com.coc.docs.web.ApiException;
import dev.samstevens.totp.code.DefaultCodeGenerator;
import dev.samstevens.totp.exceptions.CodeGenerationException;
import dev.samstevens.totp.time.SystemTimeProvider;
import dev.samstevens.totp.time.TimeProvider;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * The TOTP setup, disable, and login-redemption flows against a real
 * PostgreSQL (ADR-031). Mirrors {@link RegistrationOtpServiceIntegrationTest}:
 * what is under test is the challenge/enrollment lifecycle and the replay
 * guard, not the TOTP library itself.
 *
 * <p>// req: FR-AUTH-009, ADR-031
 */
class TotpServiceIntegrationTest extends PostgresTestBase {

    @Autowired TotpService totp;
    @Autowired AccountRepository accounts;
    @Autowired MfaLoginChallengeRepository mfaChallenges;
    @Autowired JdbcClient jdbc;
    @Autowired PasswordEncoder passwordEncoder;

    private static final String PASSWORD = "Password123!";

    @BeforeEach
    void setUp() {
        jdbc.sql("truncate audit_log, app_session, mfa_login_challenge, account_totp, account "
                        + "restart identity cascade")
                .update();

        // Deferred constraint trigger (V4): a bootstrap super_admin keeps
        // every commit in this suite from tripping the "at least one active
        // super_admin must remain" guard, same fixture every other
        // integration test in this package sets up and for the same reason.
        accounts.insert("boss@example.com", "Boss", AppRole.SUPER_ADMIN, null, AccountStatus.ACTIVE);
    }

    private CurrentUser newUser(String email) {
        Account account = accounts.insert(
                email, "Test User", AppRole.USER, null, AccountStatus.ACTIVE,
                passwordEncoder.encode(PASSWORD), false);
        return new CurrentUser(account, TestPermissions.of(AppRole.USER), java.util.Set.of(), new byte[] {1, 2, 3});
    }

    /** A Google-only account: no password_hash at all (revision of ADR-031). */
    private CurrentUser newGoogleOnlyUser(String email) {
        Account account = accounts.insert(email, "Test User", AppRole.USER, null, AccountStatus.ACTIVE);
        return new CurrentUser(account, TestPermissions.of(AppRole.USER), java.util.Set.of(), new byte[] {1, 2, 3});
    }

    /** A real, currently-valid code for {@code secret} — what an authenticator app would show right now. */
    private static String currentCodeFor(String secret) {
        try {
            TimeProvider timeProvider = new SystemTimeProvider();
            long step = Math.floorDiv(timeProvider.getTime(), 30);
            return new DefaultCodeGenerator().generate(secret, step);
        } catch (CodeGenerationException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("startSetup refuses an account with no password (revision of ADR-031)")
    void startSetupRefusesAGoogleOnlyAccount() {
        CurrentUser caller = newGoogleOnlyUser("google.only@example.com");

        assertThatThrownBy(() -> totp.startSetup(caller))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Set a password");
        assertThat(totp.isEnabledFor(caller.id())).isFalse();
    }

    @Test
    @DisplayName("startSetup mints a secret and a scannable QR code, not yet enabled")
    void startSetupMintsSecretAndQrCode() {
        CurrentUser caller = newUser("new.totp@example.com");

        TotpService.SetupMaterial material = totp.startSetup(caller);

        assertThat(material.secret()).isNotBlank();
        assertThat(material.qrCodeDataUri()).startsWith("data:image/png;base64,");
        assertThat(material.accountLabel()).isEqualTo(caller.email());
        assertThat(totp.isEnabledFor(caller.id())).isFalse();
    }

    @Test
    @DisplayName("confirmSetup with a real code enables it")
    void confirmSetupWithARealCodeEnablesIt() {
        CurrentUser caller = newUser("confirm.ok@example.com");
        TotpService.SetupMaterial material = totp.startSetup(caller);

        totp.confirmSetup(caller, currentCodeFor(material.secret()));

        assertThat(totp.isEnabledFor(caller.id())).isTrue();
    }

    @Test
    @DisplayName("confirmSetup refuses a wrong code and leaves enrollment unconfirmed")
    void confirmSetupRefusesAWrongCode() {
        CurrentUser caller = newUser("confirm.wrong@example.com");
        totp.startSetup(caller);

        assertThatThrownBy(() -> totp.confirmSetup(caller, "000000"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("incorrect");
        assertThat(totp.isEnabledFor(caller.id())).isFalse();
    }

    @Test
    @DisplayName("confirmSetup refuses when no setup is in progress")
    void confirmSetupRefusesWithNoSetupInProgress() {
        CurrentUser caller = newUser("no.setup@example.com");

        assertThatThrownBy(() -> totp.confirmSetup(caller, "123456"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("No two-factor setup is in progress");
    }

    @Test
    @DisplayName("starting setup twice while still pending replaces the secret instead of erroring")
    void startSetupTwiceWhilePendingReplacesTheSecret() {
        // Regression test: this used to delete-then-insert, which raced under
        // concurrent calls (React Strict Mode double-invoking the setup
        // wizard's effect in development reproduced this against a real
        // server) and threw DuplicateKeyException on account_totp_account_uq.
        // upsertPending is now a single atomic INSERT ... ON CONFLICT.
        CurrentUser caller = newUser("restart.pending@example.com");

        TotpService.SetupMaterial first = totp.startSetup(caller);
        TotpService.SetupMaterial second = totp.startSetup(caller);

        assertThat(second.secret()).isNotEqualTo(first.secret());
        // The second secret is the one that actually gates confirmation now.
        totp.confirmSetup(caller, currentCodeFor(second.secret()));
        assertThat(totp.isEnabledFor(caller.id())).isTrue();
    }

    @Test
    @DisplayName("startSetup refuses to replace an already-confirmed enrollment")
    void startSetupRefusesWhenAlreadyConfirmed() {
        CurrentUser caller = newUser("already.enabled@example.com");
        TotpService.SetupMaterial material = totp.startSetup(caller);
        totp.confirmSetup(caller, currentCodeFor(material.secret()));

        assertThatThrownBy(() -> totp.startSetup(caller))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("already enabled");
    }

    @Test
    @DisplayName("disable refuses a wrong password and leaves TOTP enabled")
    void disableRefusesAWrongPassword() {
        CurrentUser caller = newUser("disable.wrong@example.com");
        TotpService.SetupMaterial material = totp.startSetup(caller);
        totp.confirmSetup(caller, currentCodeFor(material.secret()));

        assertThatThrownBy(() -> totp.disable(caller, "not-the-password"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("incorrect");
        assertThat(totp.isEnabledFor(caller.id())).isTrue();
    }

    @Test
    @DisplayName("disable turns it off with the correct password, and re-enrolling starts fresh")
    void disableTurnsItOffAndAllowsReenrollment() {
        CurrentUser caller = newUser("disable.ok@example.com");
        TotpService.SetupMaterial first = totp.startSetup(caller);
        totp.confirmSetup(caller, currentCodeFor(first.secret()));

        totp.disable(caller, PASSWORD);

        assertThat(totp.isEnabledFor(caller.id())).isFalse();
        TotpService.SetupMaterial second = totp.startSetup(caller);
        assertThat(second.secret()).isNotEqualTo(first.secret());
    }

    @Test
    @DisplayName("verifyLoginChallenge completes sign-in on a correct code and spends the challenge")
    void verifyLoginChallengeCompletesSignIn() {
        CurrentUser caller = newUser("login.ok@example.com");
        TotpService.SetupMaterial material = totp.startSetup(caller);
        totp.confirmSetup(caller, currentCodeFor(material.secret()));

        String rawToken = "raw-challenge-token-1";
        mfaChallenges.insert(caller.id(), SessionService.hash(rawToken), Instant.now().plusSeconds(300));

        // A fresh step's code -- confirmSetup already spent the step above.
        String code = nextAvailableCode(material.secret());
        Account result = totp.verifyLoginChallenge(rawToken, code);

        assertThat(result.id()).isEqualTo(caller.id());
        assertThat(mfaChallenges.find(SessionService.hash(rawToken))).isEmpty();
    }

    @Test
    @DisplayName("verifyLoginChallenge refuses an expired challenge and deletes it outright")
    void verifyLoginChallengeRefusesAnExpiredChallenge() {
        CurrentUser caller = newUser("login.expired@example.com");
        TotpService.SetupMaterial material = totp.startSetup(caller);
        totp.confirmSetup(caller, currentCodeFor(material.secret()));

        String rawToken = "raw-challenge-token-2";
        mfaChallenges.insert(caller.id(), SessionService.hash(rawToken), Instant.now().minusSeconds(1));

        assertThatThrownBy(() -> totp.verifyLoginChallenge(rawToken, "000000"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("expired");
        assertThat(mfaChallenges.find(SessionService.hash(rawToken))).isEmpty();
    }

    @Test
    @DisplayName("verifyLoginChallenge refuses an unknown token")
    void verifyLoginChallengeRefusesAnUnknownToken() {
        assertThatThrownBy(() -> totp.verifyLoginChallenge("never-issued", "123456"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("expired");
    }

    @Test
    @DisplayName("verifyLoginChallenge refuses a wrong code but leaves the challenge retryable")
    void verifyLoginChallengeRefusesAWrongCodeButLeavesChallengeRetryable() {
        CurrentUser caller = newUser("login.wrong@example.com");
        TotpService.SetupMaterial material = totp.startSetup(caller);
        totp.confirmSetup(caller, currentCodeFor(material.secret()));

        String rawToken = "raw-challenge-token-3";
        byte[] tokenHash = SessionService.hash(rawToken);
        mfaChallenges.insert(caller.id(), tokenHash, Instant.now().plusSeconds(300));

        assertThatThrownBy(() -> totp.verifyLoginChallenge(rawToken, "000000"))
                .isInstanceOf(ApiException.class);
        assertThat(mfaChallenges.find(tokenHash)).isPresent();
    }

    @Test
    @DisplayName("a code already used to confirm setup cannot be replayed to sign in")
    void verifyLoginChallengeRefusesACodeAlreadyUsedDuringSetup() {
        CurrentUser caller = newUser("replay@example.com");
        TotpService.SetupMaterial material = totp.startSetup(caller);
        String code = currentCodeFor(material.secret());
        totp.confirmSetup(caller, code); // this step is now spent

        String rawToken = "raw-challenge-token-4";
        mfaChallenges.insert(caller.id(), SessionService.hash(rawToken), Instant.now().plusSeconds(300));

        assertThatThrownBy(() -> totp.verifyLoginChallenge(rawToken, code))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("already been used");
    }

    /** A code for a step after {@code secret}'s last confirmed use, so replay protection doesn't refuse it too. */
    private static String nextAvailableCode(String secret) {
        try {
            long step = Math.floorDiv(new SystemTimeProvider().getTime(), 30) + 1;
            return new DefaultCodeGenerator().generate(secret, step);
        } catch (CodeGenerationException e) {
            throw new IllegalStateException(e);
        }
    }
}
