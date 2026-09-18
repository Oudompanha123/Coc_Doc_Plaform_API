package com.coc.docs.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.coc.docs.PostgresTestBase;
import com.coc.docs.account.Account;
import com.coc.docs.account.AccountRepository;
import com.coc.docs.account.AccountStatus;
import com.coc.docs.account.AppRole;
import com.coc.docs.account.Permission;
import com.coc.docs.capability.Capability;
import com.coc.docs.web.ApiException;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * {@code SessionService.changeOwnPassword} against a real PostgreSQL —
 * specifically the current-password rule ADR-022 added (revision of
 * ADR-020), ADR-024 made unconditional for an existing hash, and ADR-030
 * gave a third, hash-free branch. {@code AuthControllerTest.ChangePassword}
 * covers the HTTP/DTO layer with a mocked {@code SessionService}; this is
 * where the actual business rule is exercised.
 *
 * <p>// req: FR-AUTH-002, FR-AUTH-007, ADR-017, ADR-020, ADR-022, ADR-024, ADR-030
 */
class ChangeOwnPasswordIntegrationTest extends PostgresTestBase {

    @Autowired SessionService sessions;
    @Autowired AccountRepository accounts;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JdbcClient jdbc;

    @BeforeEach
    void setUp() {
        jdbc.sql("truncate audit_log, app_session, account restart identity cascade").update();

        // Deferred constraint trigger (V4): a bootstrap super_admin keeps every
        // commit in this suite from tripping the "at least one active
        // super_admin must remain" guard, same fixture every other
        // integration test in this package sets up and for the same reason.
        accounts.insert("boss@example.com", "Boss", AppRole.SUPER_ADMIN, null, AccountStatus.ACTIVE);
    }

    /**
     * Signs in with a real password and resolves the resulting token back into
     * a real {@code CurrentUser}. Cast is safe here: no fixture in this suite
     * enrolls TOTP (ADR-031), so {@code signInWithPassword} always returns a
     * real session, never {@code MfaRequired}.
     */
    private CurrentUser signInAndResolve(String email, String rawPassword) {
        SessionService.IssuedSession issued =
                (SessionService.IssuedSession) sessions.signInWithPassword(email, rawPassword, "test-agent", "127.0.0.1");
        return sessions.resolve(issued.token()).orElseThrow();
    }

    private String hashOf(String email) {
        return accounts.findCredentialByEmailIgnoreCase(email).orElseThrow().passwordHash();
    }

    @Test
    @DisplayName("voluntary change succeeds when the current password is right")
    void voluntaryChangeSucceeds() {
        accounts.insert(
                "voluntary@example.com", "Voluntary", AppRole.USER, null, AccountStatus.ACTIVE,
                passwordEncoder.encode("OldPassword123!"), false);
        CurrentUser caller = signInAndResolve("voluntary@example.com", "OldPassword123!");

        sessions.changeOwnPassword(caller, "OldPassword123!", "NewPassword456!");

        String newHash = hashOf("voluntary@example.com");
        assertThat(passwordEncoder.matches("NewPassword456!", newHash)).isTrue();
        assertThat(passwordEncoder.matches("OldPassword123!", newHash)).isFalse();
    }

    @Test
    @DisplayName("voluntary change is refused when the current password is wrong, and nothing changes")
    void voluntaryChangeRefusesWrongCurrentPassword() {
        accounts.insert(
                "voluntary2@example.com", "Voluntary", AppRole.USER, null, AccountStatus.ACTIVE,
                passwordEncoder.encode("OldPassword123!"), false);
        CurrentUser caller = signInAndResolve("voluntary2@example.com", "OldPassword123!");

        assertThatThrownBy(() -> sessions.changeOwnPassword(caller, "WrongPassword!!!", "NewPassword456!"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getMessage()).isEqualTo("your current password is incorrect");
                });

        assertThat(passwordEncoder.matches("OldPassword123!", hashOf("voluntary2@example.com"))).isTrue();
    }

    @Test
    @DisplayName("voluntary change is refused when no current password is given at all")
    void voluntaryChangeRefusesMissingCurrentPassword() {
        accounts.insert(
                "voluntary3@example.com", "Voluntary", AppRole.USER, null, AccountStatus.ACTIVE,
                passwordEncoder.encode("OldPassword123!"), false);
        CurrentUser caller = signInAndResolve("voluntary3@example.com", "OldPassword123!");

        assertThatThrownBy(() -> sessions.changeOwnPassword(caller, null, "NewPassword456!"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> sessions.changeOwnPassword(caller, "   ", "NewPassword456!"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    @DisplayName("the forced first-login completion also verifies the temporary password (ADR-024)")
    void forcedFirstLoginVerifiesTheTemporaryPassword() {
        accounts.insert(
                "invited@example.com", "Invited", AppRole.USER, null, AccountStatus.ACTIVE,
                passwordEncoder.encode("TempPassword123!"), true);
        CurrentUser caller = signInAndResolve("invited@example.com", "TempPassword123!");
        assertThat(caller.account().mustChangePassword()).isTrue();

        sessions.changeOwnPassword(caller, "TempPassword123!", "NewPassword456!");

        assertThat(passwordEncoder.matches("NewPassword456!", hashOf("invited@example.com"))).isTrue();
    }

    @Test
    @DisplayName("the forced first-login completion is refused when the temporary password is wrong (ADR-024)")
    void forcedFirstLoginRefusesWrongTemporaryPassword() {
        accounts.insert(
                "invited2@example.com", "Invited", AppRole.USER, null, AccountStatus.ACTIVE,
                passwordEncoder.encode("TempPassword123!"), true);
        CurrentUser caller = signInAndResolve("invited2@example.com", "TempPassword123!");

        assertThatThrownBy(() -> sessions.changeOwnPassword(caller, "WrongTemp123!", "NewPassword456!"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getMessage()).isEqualTo("your current password is incorrect");
                });

        assertThat(passwordEncoder.matches("TempPassword123!", hashOf("invited2@example.com"))).isTrue();
    }

    @Test
    @DisplayName("the forced first-login completion is refused when no temporary password is given at all (ADR-030 no longer stops this at the DTO layer)")
    void forcedFirstLoginRefusesMissingTemporaryPassword() {
        accounts.insert(
                "invited3@example.com", "Invited", AppRole.USER, null, AccountStatus.ACTIVE,
                passwordEncoder.encode("TempPassword123!"), true);
        CurrentUser caller = signInAndResolve("invited3@example.com", "TempPassword123!");

        assertThatThrownBy(() -> sessions.changeOwnPassword(caller, null, "NewPassword456!"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getMessage()).isEqualTo("your current password is incorrect");
                });
    }

    @Test
    @DisplayName("still refuses reusing the current password as the new one (ADR-020)")
    void refusesReusingCurrentPassword() {
        accounts.insert(
                "reuse@example.com", "Reuse", AppRole.USER, null, AccountStatus.ACTIVE,
                passwordEncoder.encode("SamePassword123!"), false);
        CurrentUser caller = signInAndResolve("reuse@example.com", "SamePassword123!");

        assertThatThrownBy(() ->
                        sessions.changeOwnPassword(caller, "SamePassword123!", "SamePassword123!"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getMessage()).isEqualTo("choose a password different from your current one");
                });
    }

    @Test
    @DisplayName("a Google-only account sets an initial password, needing no current one (ADR-030)")
    void setsInitialPasswordForGoogleOnlyAccount() {
        Account googleOnly = accounts.insert("google.only@example.com", "Google Only", AppRole.USER, null, AccountStatus.ACTIVE);
        assertThat(googleOnly.hasPassword()).isFalse();
        CurrentUser caller =
                new CurrentUser(googleOnly, Set.<Permission>of(), Set.<Capability>of(), new byte[] {9, 9, 9});

        // No current password supplied at all — there is none to prove.
        sessions.changeOwnPassword(caller, null, "NewPassword456!");

        String hash = hashOf("google.only@example.com");
        assertThat(hash).isNotNull();
        assertThat(passwordEncoder.matches("NewPassword456!", hash)).isTrue();
    }

    @Test
    @DisplayName("a Google-only account still cannot reuse a wrong or irrelevant 'current' password as an excuse to skip the rule for others")
    void initialPasswordSetIgnoresAnyCurrentPasswordSupplied() {
        Account googleOnly = accounts.insert("google.only2@example.com", "Google Only", AppRole.USER, null, AccountStatus.ACTIVE);
        CurrentUser caller =
                new CurrentUser(googleOnly, Set.<Permission>of(), Set.<Capability>of(), new byte[] {9, 9, 9});

        // Whatever is sent here is simply irrelevant — there is no hash to
        // check it against — so this still succeeds rather than being
        // refused as "incorrect".
        sessions.changeOwnPassword(caller, "whatever-nonsense", "NewPassword456!");

        assertThat(passwordEncoder.matches("NewPassword456!", hashOf("google.only2@example.com"))).isTrue();
    }

    @Test
    @DisplayName("revokes every other live session but keeps the caller's own (FR-AUTH-007)")
    void revokesOtherSessionsButNotItsOwn() {
        accounts.insert(
                "multisession@example.com", "Multi Session", AppRole.USER, null, AccountStatus.ACTIVE,
                passwordEncoder.encode("OldPassword123!"), false);

        SessionService.IssuedSession first = (SessionService.IssuedSession)
                sessions.signInWithPassword("multisession@example.com", "OldPassword123!", "agent-1", "127.0.0.1");
        SessionService.IssuedSession second = (SessionService.IssuedSession)
                sessions.signInWithPassword("multisession@example.com", "OldPassword123!", "agent-2", "127.0.0.2");
        CurrentUser caller = sessions.resolve(first.token()).orElseThrow();

        sessions.changeOwnPassword(caller, "OldPassword123!", "NewPassword456!");

        assertThat(sessions.resolve(first.token())).isPresent();
        assertThat(sessions.resolve(second.token())).isEmpty();
    }
}
