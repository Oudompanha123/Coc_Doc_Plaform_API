package com.coc.docs.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.coc.docs.PostgresTestBase;
import com.coc.docs.account.AccountRepository;
import com.coc.docs.account.AccountStatus;
import com.coc.docs.account.AppRole;
import com.coc.docs.audit.AuditAction;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * {@link SessionService#recentSignIns} — the Settings &gt; Security card's
 * "Recent sign-ins" (ADR-090).
 *
 * <p>Against a real database for the same reason {@link NewDeviceSignInTest}
 * is: the answer depends on rows already in {@code audit_log}, their order,
 * and which account they belong to.
 *
 * <p>// req: FR-OPS-001, NFR-SEC-PII, ADR-090
 */
class RecentSignInsTest extends PostgresTestBase {

    private static final String CHROME =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/140.0.0.0 Safari/537.36";
    private static final String PASSWORD = "CorrectHorse123!";

    @Autowired SessionService sessions;
    @Autowired AccountRepository accounts;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JdbcClient jdbc;

    @BeforeEach
    void setUp() {
        jdbc.sql("truncate audit_log, app_session, account restart identity cascade").update();
        accounts.insert("boss@example.com", "Boss", AppRole.SUPER_ADMIN, null, AccountStatus.ACTIVE);
        accounts.insert(
                "user@example.com",
                "User",
                AppRole.USER,
                null,
                AccountStatus.ACTIVE,
                passwordEncoder.encode(PASSWORD),
                false);
        accounts.insert(
                "other@example.com",
                "Other",
                AppRole.USER,
                null,
                AccountStatus.ACTIVE,
                passwordEncoder.encode(PASSWORD),
                false);
    }

    private CurrentUser signIn(String email) {
        SessionService.IssuedSession issued =
                (SessionService.IssuedSession) sessions.signInWithPassword(email, PASSWORD, CHROME, "203.0.113.5");
        return sessions.resolve(issued.token()).orElseThrow();
    }

    @Test
    @DisplayName("a sign-in shows up with its device string and no IP")
    void signInCarriesDeviceNoIp() {
        CurrentUser caller = signIn("user@example.com");

        List<SessionService.SignInHistoryEntry> events = sessions.recentSignIns(caller, 10);

        assertThat(events).singleElement().satisfies(e -> {
            assertThat(e.action()).isEqualTo(AuditAction.AUTH_SIGN_IN);
            assertThat(e.device()).isEqualTo("Chrome on Windows");
        });
    }

    @Test
    @DisplayName("a sign-out shows up too, with a blank device rather than null")
    void signOutHasNoDeviceButIsPresent() {
        CurrentUser caller = signIn("user@example.com");
        var own = sessions.listOwnSessions(caller);
        sessions.revokeOwnSession(caller, own.get(0).id());

        List<SessionService.SignInHistoryEntry> events = sessions.recentSignIns(caller, 10);

        assertThat(events).hasSize(2);
        assertThat(events.get(0).action()).isEqualTo(AuditAction.AUTH_SIGN_OUT);
        assertThat(events.get(0).device()).isEmpty();
        assertThat(events.get(1).action()).isEqualTo(AuditAction.AUTH_SIGN_IN);
    }

    @Test
    @DisplayName("newest first")
    void newestFirst() {
        CurrentUser caller = signIn("user@example.com");
        signIn("user@example.com");
        signIn("user@example.com");

        List<SessionService.SignInHistoryEntry> events = sessions.recentSignIns(caller, 10);

        assertThat(events).hasSize(3);
        assertThat(events).isSortedAccordingTo((a, b) -> b.occurredAt().compareTo(a.occurredAt()));
    }

    @Test
    @DisplayName("respects the limit, keeping only the most recent")
    void respectsLimit() {
        CurrentUser caller = null;
        for (int i = 0; i < 5; i++) {
            caller = signIn("user@example.com");
        }

        assertThat(sessions.recentSignIns(caller, 3)).hasSize(3);
    }

    @Test
    @DisplayName("one account's history says nothing about another's")
    void scopedToTheCallerAlone() {
        signIn("other@example.com");
        signIn("other@example.com");
        CurrentUser caller = signIn("user@example.com");

        assertThat(sessions.recentSignIns(caller, 10)).hasSize(1);
    }
}
