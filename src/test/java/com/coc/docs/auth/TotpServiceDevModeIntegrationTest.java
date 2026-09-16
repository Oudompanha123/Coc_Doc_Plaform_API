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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;

/**
 * The developer/test fake-code bypass (ADR-031), against a real PostgreSQL,
 * under its own Spring context so {@code app.otp.enabled=false} applies only
 * here — every other test in this package runs with the secure default.
 *
 * <p>Safe to run at all: the {@code test} Spring profile
 * ({@link PostgresTestBase}'s {@code @ActiveProfiles("test")}) is exactly
 * what {@link OtpDevModeGuard} requires for this bypass to be permitted.
 *
 * <p>// req: FR-AUTH-009, ADR-031
 */
@TestPropertySource(properties = {"app.otp.enabled=false", "app.otp.dev-code=654321"})
class TotpServiceDevModeIntegrationTest extends PostgresTestBase {

    @Autowired TotpService totp;
    @Autowired AccountRepository accounts;
    @Autowired JdbcClient jdbc;
    @Autowired PasswordEncoder passwordEncoder;

    @BeforeEach
    void setUp() {
        jdbc.sql("truncate audit_log, app_session, mfa_login_challenge, account_totp, account "
                        + "restart identity cascade")
                .update();
        accounts.insert("boss@example.com", "Boss", AppRole.SUPER_ADMIN, null, AccountStatus.ACTIVE);
    }

    private CurrentUser newUser(String email) {
        Account account = accounts.insert(
                email, "Test User", AppRole.USER, null, AccountStatus.ACTIVE,
                passwordEncoder.encode("Password123!"), false);
        return new CurrentUser(account, TestPermissions.of(AppRole.USER), new byte[] {1, 2, 3});
    }

    @Test
    @DisplayName("accepts the configured dev code instead of a real TOTP code")
    void acceptsTheConfiguredDevCode() {
        CurrentUser caller = newUser("dev.user@example.com");
        totp.startSetup(caller);

        // The configured dev code, not a code derived from the real secret --
        // the whole point is skipping the need for a real authenticator app.
        totp.confirmSetup(caller, "654321");

        assertThat(totp.isEnabledFor(caller.id())).isTrue();
    }

    @Test
    @DisplayName("still refuses a code that is neither the dev code nor a real one")
    void refusesAnythingElse() {
        CurrentUser caller = newUser("dev.wrong@example.com");
        totp.startSetup(caller);

        assertThatThrownBy(() -> totp.confirmSetup(caller, "000001"))
                .isInstanceOf(ApiException.class);
        assertThat(totp.isEnabledFor(caller.id())).isFalse();
    }
}
