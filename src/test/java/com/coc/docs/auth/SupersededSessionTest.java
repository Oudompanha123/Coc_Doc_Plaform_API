package com.coc.docs.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.coc.docs.PostgresTestBase;
import com.coc.docs.account.Account;
import com.coc.docs.account.AccountRepository;
import com.coc.docs.account.AccountStatus;
import com.coc.docs.account.AppRole;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Signing in again in the same browser ends the session it replaces.
 *
 * <p>Against a real database because the claim is about rows: how many sessions
 * are live for an account afterwards, and which one.
 *
 * <p>// req: FR-AUTH-007, NFR-SEC-SESSION
 */
class SupersededSessionTest extends PostgresTestBase {

    @Autowired SessionService sessions;
    @Autowired SessionRepository sessionRepository;
    @Autowired AccountRepository accounts;
    @Autowired JdbcClient jdbc;

    private Account person;

    @BeforeEach
    void setUp() {
        jdbc.sql("truncate audit_log, app_session, account restart identity cascade").update();
        person = accounts.insert(
                "person@example.com", "Person", AppRole.USER, null, AccountStatus.ACTIVE);
    }

    private String liveSession(String token) {
        sessionRepository.insert(
                sha256(token), person.id(), Instant.now().plusSeconds(3600), "Chrome", "127.0.0.1");
        return token;
    }

    /** Same hashing the service uses — the test would pass on anything otherwise. */
    private static byte[] sha256(String token) {
        try {
            return java.security.MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private long liveCount() {
        Long count = jdbc.sql(
                        "select count(*) from app_session where account_id = ? and revoked_at is null")
                .param(person.id())
                .query(Long.class)
                .single();
        return count == null ? 0 : count;
    }

    @Test
    @DisplayName("the replaced session is revoked, not left running")
    void supersededSessionIsRevoked() {
        String old = liveSession("old-token");
        assertThat(liveCount()).isEqualTo(1);

        sessions.revokeSuperseded(old);

        // Signing in again overwrites the cookie, so this session is
        // unreachable by its owner. Left live it is a working credential
        // nobody holds, for as long as the TTL runs.
        assertThat(liveCount()).isZero();
        assertThat(sessionRepository.find(sha256(old)).orElseThrow().isLive(Instant.now())).isFalse();
    }

    @Test
    @DisplayName("sessions in other browsers are untouched")
    void otherSessionsSurvive() {
        String thisBrowser = liveSession("this-browser");
        liveSession("other-browser");
        assertThat(liveCount()).isEqualTo(2);

        sessions.revokeSuperseded(thisBrowser);

        // The cookie names one session, never a device or a browser family.
        // Matching on user agent instead would sign somebody out of their own
        // machine for sharing a Chrome version.
        assertThat(liveCount()).isEqualTo(1);
        assertThat(sessionRepository.find(sha256("other-browser")).orElseThrow().isLive(Instant.now()))
                .isTrue();
    }

    @Test
    @DisplayName("a first sign-in, carrying no cookie, is not an error")
    void noPreviousTokenIsFine() {
        sessions.revokeSuperseded(null);
        sessions.revokeSuperseded("");

        assertThat(liveCount()).isZero();
    }

    @Test
    @DisplayName("a cookie naming nothing is ignored quietly")
    void unknownTokenIsIgnored() {
        liveSession("real");

        // An expired session, or a cookie left over from a rebuilt database.
        // Neither is a fault, and neither should disturb what is live.
        sessions.revokeSuperseded("not-a-session");

        assertThat(liveCount()).isEqualTo(1);
    }
}
