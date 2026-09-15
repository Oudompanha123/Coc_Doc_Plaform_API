package com.coc.docs.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.coc.docs.testsupport.KeycloakTestTokens;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * No Spring context, no database, no network — pure unit tests of the
 * verification logic against a locally-signed token. // req: FR-AUTH-001, TM-001
 */
class KeycloakIdTokenServiceTest {

    private final KeycloakTestTokens tokens = new KeycloakTestTokens();

    private KeycloakIdTokenService service() {
        return new KeycloakIdTokenService(tokens.decoder(), KeycloakTestTokens.AUDIENCE, new MockEnvironment());
    }

    @Test
    void verifiesAWellFormedToken() {
        String jwt = tokens.sign(tokens.defaultClaims());

        VerifiedIdentity identity = service().verify(jwt, "expected-nonce");

        assertThat(identity.email()).isEqualTo("person@example.com");
        assertThat(identity.displayName()).isEqualTo("Person Example");
        assertThat(identity.avatarUrl()).isNull();
    }

    /**
     * The claim Google itself returns, once a realm's Google identity
     * provider is configured to forward it (ADR-013). This test only proves
     * this class's own parsing is correct; it says nothing about whether a
     * given Keycloak realm is actually configured to forward the claim.
     */
    @Test
    void capturesTheAvatarUrlWhenThePictureClaimIsPresent() {
        Map<String, Object> claims = tokens.defaultClaims();
        claims.put("picture", "https://lh3.googleusercontent.com/a/example");

        VerifiedIdentity identity = service().verify(tokens.sign(claims), "expected-nonce");

        assertThat(identity.avatarUrl()).isEqualTo("https://lh3.googleusercontent.com/a/example");
    }

    /** javascript:/data: have no business reaching an <img src>; only a fetchable https URL is trusted. */
    @Test
    void dropsANonHttpsPictureClaim() {
        Map<String, Object> claims = tokens.defaultClaims();
        claims.put("picture", "javascript:alert(1)");

        VerifiedIdentity identity = service().verify(tokens.sign(claims), "expected-nonce");

        assertThat(identity.avatarUrl()).isNull();
    }

    @Test
    void rejectsATokenSignedByAnUntrustedKey() {
        KeycloakTestTokens attacker = new KeycloakTestTokens();
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
        assertThatThrownBy(() -> new KeycloakIdTokenService(tokens.decoder(), "", new MockEnvironment()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void skipsTheFailFastCheckUnderTheTestProfile() {
        MockEnvironment testProfile = new MockEnvironment();
        testProfile.addActiveProfile("test");

        // Must not throw, even with a blank audience.
        new KeycloakIdTokenService(tokens.decoder(), "", testProfile);
    }

    private static void assertReasonIs(String expectedReason, Runnable verifyCall) {
        assertThatThrownBy(verifyCall::run)
                .isInstanceOf(AuthenticationFailedException.class)
                .satisfies(e -> assertThat(((AuthenticationFailedException) e).reason()).isEqualTo(expectedReason));
    }
}
