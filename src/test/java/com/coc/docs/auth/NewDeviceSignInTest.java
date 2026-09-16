package com.coc.docs.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.coc.docs.PostgresTestBase;
import com.coc.docs.account.AccountRepository;
import com.coc.docs.account.AccountStatus;
import com.coc.docs.account.AppRole;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * When a sign-in counts as coming from a device the account has not used.
 *
 * <p>Against a real database because the claim is entirely about rows that
 * already exist: the answer depends on every prior session for the account,
 * including revoked and expired ones, and on the order the rows were written.
 *
 * <p>// req: FR-AUTH-007, NFR-SEC-SESSION, ADR-027
 */
class NewDeviceSignInTest extends PostgresTestBase {

    private static final String CHROME =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/140.0.0.0 Safari/537.36";
    private static final String FIREFOX =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:130.0) Gecko/20100101 Firefox/130.0";
    private static final String PASSWORD = "CorrectHorse123!";

    @Autowired SessionService sessions;
    @Autowired SessionRepository sessionRepository;
    @Autowired AccountRepository accounts;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JdbcClient jdbc;

    @BeforeEach
    void setUp() {
        jdbc.sql("truncate audit_log, app_session, account restart identity cascade").update();
        // The V4 deferred trigger requires an active super_admin to survive
        // every commit — the same fixture the other integration tests here use.
        accounts.insert("boss@example.com", "Boss", AppRole.SUPER_ADMIN, null, AccountStatus.ACTIVE);
        accounts.insert(
                "user@example.com",
                "User",
                AppRole.USER,
                null,
                AccountStatus.ACTIVE,
                passwordEncoder.encode(PASSWORD),
                false);
    }

    private void signIn(String userAgent) {
        sessions.signInWithPassword("user@example.com", PASSWORD, userAgent, "127.0.0.1");
    }

    /** {@code new_device} for every session of the test account, oldest first. */
    private List<Boolean> flags() {
        return jdbc.sql(
                        """
                        select s.new_device from app_session s
                        join account a on a.id = s.account_id
                        where a.email = 'user@example.com'
                        order by s.issued_at
                        """)
                .query(Boolean.class)
                .list();
    }

    @Test
    @DisplayName("the very first sign-in is not announced as a new device")
    void firstSignInSeedsQuietly() {
        signIn(CHROME);

        // Nothing to compare against yet. Warning somebody about the browser
        // they are sitting at, on the first time they ever sign in, teaches
        // them the alert is noise before it has ever been right.
        assertThat(flags()).containsExactly(false);
    }

    @Test
    @DisplayName("signing in again from the same browser is not a new device")
    void sameDeviceIsRecognised() {
        signIn(CHROME);
        signIn(CHROME);
        signIn(CHROME);

        assertThat(flags()).containsExactly(false, false, false);
    }

    @Test
    @DisplayName("a different browser on the same machine is a new device")
    void differentBrowserIsNew() {
        signIn(CHROME);

        signIn(FIREFOX);

        // The case the report was about: the same person, the same computer,
        // a browser this account has never been used in. It counts, because
        // from the server's side it is indistinguishable from somebody else's
        // browser, which is exactly the thing worth asking about.
        assertThat(flags()).containsExactly(false, true);
    }

    @Test
    @DisplayName("a device stays recognised after its session is revoked")
    void revokedSessionsStillCount() {
        signIn(CHROME);
        signIn(FIREFOX);
        // Sign out everywhere, then come back on the browser used before.
        sessions.revokeAllFor(
                accounts.findByEmail("user@example.com").orElseThrow().id());

        signIn(CHROME);

        // "Have you used this browser" is not "are you using it now". A laptop
        // you signed out of last week is still a laptop you recognise, and
        // alerting on the ordinary act of signing back in is the fastest way
        // to make the alert worthless.
        assertThat(flags()).containsExactly(false, true, false);
    }

    @Test
    @DisplayName("the new device is flagged in the caller's own session list")
    void listedSessionCarriesTheFlag() {
        signIn(CHROME);
        SessionService.IssuedSession onFirefox =
                sessions.signInWithPassword("user@example.com", PASSWORD, FIREFOX, "127.0.0.1");

        CurrentUser caller = sessions.resolve(onFirefox.token()).orElseThrow();
        List<SessionService.OwnSession> own = sessions.listOwnSessions(caller);

        assertThat(own).hasSize(2);
        assertThat(own)
                .filteredOn(SessionService.OwnSession::newDevice)
                .singleElement()
                .satisfies(s -> assertThat(s.device()).isEqualTo("Firefox on Windows"));
        // The description the person reads comes from the server, so the list,
        // the push banner and the audit entry cannot disagree about it.
        assertThat(own).extracting(SessionService.OwnSession::device)
                .containsExactlyInAnyOrder("Firefox on Windows", "Chrome on Windows");
    }

    @Test
    @DisplayName("one account's devices say nothing about another's")
    void devicesAreNotSharedBetweenAccounts() {
        accounts.insert(
                "other@example.com",
                "Other",
                AppRole.USER,
                null,
                AccountStatus.ACTIVE,
                passwordEncoder.encode(PASSWORD),
                false);
        sessions.signInWithPassword("other@example.com", PASSWORD, CHROME, "127.0.0.1");
        sessions.signInWithPassword("other@example.com", PASSWORD, FIREFOX, "127.0.0.1");

        signIn(CHROME);
        signIn(FIREFOX);

        // Recognition is per account. A shared machine must not make somebody
        // else's first sign-in look familiar.
        assertThat(flags()).containsExactly(false, true);
    }
}
