package com.coc.docs.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.coc.docs.testsupport.GoogleTestTokens;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * No Spring context, no database, no network — pure unit tests of the
 * verification logic against a locally-signed token. // req: FR-AUTH-001, TM-001
 */
class GoogleIdTokenServiceTest {

    private final GoogleTestTokens tokens = new GoogleTestTokens();

    private GoogleIdTokenService service() {
        return new GoogleIdTokenService(tokens.decoder(), GoogleTestTokens.AUDIENCE, new MockEnvironment());
    }

    @Test
    void verifiesAWellFormedToken() {
        String jwt = tokens.sign(tokens.defaultClaims());

        VerifiedGoogleIdentity identity = service().verify(jwt, "expected-nonce");

        assertThat(identity.email()).isEqualTo("person@example.com");
        assertThat(identity.displayName()).isEqualTo("Person Example");
    }

    @Test
    void rejectsATokenSignedByAnUntrustedKey() {
        GoogleTestTokens attacker = new GoogleTestTokens();
        String forged = attacker.sign(tokens.defaultClaims());

        assertReasonIs("token_invalid", () -> service().verify(forged, "expected-nonce"));
    }

    @Test
    void rejectsTheWrongAudience() {
        Map<String, Object> claims = tokens.defaultClaims();
        claims.put("aud", "someone-elses-client-id");

        assertReasonIs("token_invalid", () -> service().verify(tokens.sign(claims), "expected-nonce"));
    }

    @Test
    void rejectsAnExpiredToken() {
        Map<String, Object> claims = tokens.defaultClaims();
        claims.put("exp", Instant.now().minusSeconds(60).getEpochSecond());

        assertReasonIs("token_invalid", () -> service().verify(tokens.sign(claims), "expected-nonce"));
    }

    @Test
    void rejectsAnUnverifiedEmail() {
        Map<String, Object> claims = tokens.defaultClaims();
        claims.put("email_verified", false);

        assertReasonIs("email_not_verified", () -> service().verify(tokens.sign(claims), "expected-nonce"));
    }

    @Test
    void rejectsAMismatchedNonce() {
        String jwt = tokens.sign(tokens.defaultClaims());

        assertReasonIs("nonce_mismatch", () -> service().verify(jwt, "a-different-nonce"));
    }

    @Test
    void rejectsAMissingExpectedNonce() {
        String jwt = tokens.sign(tokens.defaultClaims());

        assertReasonIs("missing_nonce", () -> service().verify(jwt, null));
    }

    @Test
    void failsFastAtStartupWithoutAConfiguredAudience() {
        assertThatThrownBy(() -> new GoogleIdTokenService(tokens.decoder(), "", new MockEnvironment()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void skipsTheFailFastCheckUnderTheTestProfile() {
        MockEnvironment testProfile = new MockEnvironment();
        testProfile.addActiveProfile("test");

        // Must not throw, even with a blank audience.
        new GoogleIdTokenService(tokens.decoder(), "", testProfile);
    }

    private static void assertReasonIs(String expectedReason, Runnable verifyCall) {
        assertThatThrownBy(verifyCall::run)
                .isInstanceOf(AuthenticationFailedException.class)
                .satisfies(e -> assertThat(((AuthenticationFailedException) e).reason()).isEqualTo(expectedReason));
    }
}
