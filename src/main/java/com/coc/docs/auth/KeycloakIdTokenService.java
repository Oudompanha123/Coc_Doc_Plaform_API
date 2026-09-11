package com.coc.docs.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;


/**
 * Verifies the ID token issued by Keycloak, which brokers Google upstream:
 * "the frontend never asserts who the user is; it passes a token, and the
 * backend decides whether to believe it." // req: FR-AUTH-001, TM-001
 *
 * <p>Per ADR-013 (revision of ADR-008), the backend no longer trusts Google's
 * JWK set directly. Google authenticates the user, but Keycloak sits in the
 * middle and issues its own token — this class verifies that Keycloak token,
 * not a Google one. The user experience is unchanged ("Sign in with Google",
 * the ordinary Google consent screen); only the party this class trusts moved.
 *
 * <p>Signature, issuer and audience are checked by the {@link JwtDecoder} bean
 * that {@code spring-boot-starter-oauth2-resource-server} already
 * autoconfigures from {@code application.yml}'s
 * {@code spring.security.oauth2.resourceserver.jwt.issuer-uri}/{@code audiences}
 * — that autoconfiguration fetches and caches the configured issuer's published
 * JWK set (now Keycloak's realm, not {@code accounts.google.com}) and validates
 * the token's expiry too. This class adds the two checks that autoconfiguration
 * cannot know about: the OIDC nonce (app-specific, tied to one sign-in attempt)
 * and the {@code email_verified} claim.
 *
 * <p>Roles are never read from this token (ADR-013 §2, TM-003): Keycloak only
 * answers "who is this?"; {@code account.role}, read fresh from the database
 * on every request, answers "what may they do here?".
 */
// Not @Service: declared by KeycloakAuthConfig, which is conditional on a
// configured realm. Component scanning it would construct this on every
// deployment, including those with no Keycloak, and fail at startup looking
// for a JwtDecoder that was never meant to exist.
public class KeycloakIdTokenService {

    private final JwtDecoder jwtDecoder;

    public KeycloakIdTokenService(
            JwtDecoder jwtDecoder,
            @Value("${spring.security.oauth2.resourceserver.jwt.audiences:}") String configuredAudiences,
            Environment environment) {
        this.jwtDecoder = jwtDecoder;
        boolean isTestProfile = Arrays.asList(environment.getActiveProfiles()).contains("test");
        if (!isTestProfile && (configuredAudiences == null || configuredAudiences.isBlank())) {
            // Spring's autoconfigured audience validator only activates when
            // `audiences` is non-empty. Left unset, a token minted by the
            // Keycloak realm for ANY client would verify successfully here —
            // silently reintroducing the exact "believe the frontend" failure
            // this service exists to close. Fail the deploy, not a login
            // attempt.
            throw new IllegalStateException(
                    "The resource-server audience (spring.security.oauth2.resourceserver.jwt.audiences — this "
                            + "app's Keycloak client id) is not set. Refusing to start: left unset, audience "
                            + "verification is silently skipped, not merely permissive.");
        }
    }

    /**
     * @param idToken the raw JWT issued by Keycloak after it brokers the
     *     Google sign-in.
     * @param expectedNonce the value read from this browser's {@code coc_oidc_nonce}
     *     cookie — never from anything the request body claims.
     * @throws AuthenticationFailedException on any failed check. The reason
     *     string is for the audit log; see that exception's Javadoc for why
     *     it must never reach the HTTP response.
     */
    public VerifiedIdentity verify(String idToken, String expectedNonce) {
        if (expectedNonce == null || expectedNonce.isBlank()) {
            throw new AuthenticationFailedException("missing_nonce");
        }
        Jwt jwt;
        try {
            jwt = jwtDecoder.decode(idToken);
        } catch (JwtException e) {
            throw new AuthenticationFailedException("token_invalid");
        }

        if (!Boolean.TRUE.equals(jwt.getClaimAsBoolean("email_verified"))) {
            throw new AuthenticationFailedException("email_not_verified");
        }

        String email = jwt.getClaimAsString("email");
        if (email == null || email.isBlank()) {
            throw new AuthenticationFailedException("email_claim_missing");
        }

        String nonce = jwt.getClaimAsString("nonce");
        if (nonce == null || !constantTimeEquals(nonce, expectedNonce)) {
            throw new AuthenticationFailedException("nonce_mismatch");
        }

        String name = jwt.getClaimAsString("name");

        // Optional, and trusted only as far as it goes: a URL the identity
        // provider chose, stored as text and rendered as an image. It is never
        // fetched server-side and never used in a decision, so a hostile value
        // is a broken avatar rather than a request this backend was tricked
        // into making. Anything that is not plain https is dropped —
        // javascript: and data: URLs have no business reaching an img tag.
        String picture = jwt.getClaimAsString("picture");
        if (picture != null && !picture.startsWith("https://")) {
            picture = null;
        }

        return new VerifiedIdentity(
                email, (name == null || name.isBlank()) ? email : name, picture);
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
