package com.coc.docs.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Service;

/**
 * Verifies a Google ID token the way the ticket demands: "the frontend never
 * asserts who the user is; it passes a token, and the backend decides whether
 * to believe it." // req: FR-AUTH-001, TM-001
 *
 * <p>Signature, issuer and audience are checked by the {@link JwtDecoder} bean
 * that {@code spring-boot-starter-oauth2-resource-server} already
 * autoconfigures from {@code application.yml}'s
 * {@code spring.security.oauth2.resourceserver.jwt.issuer-uri}/{@code audiences}
 * — that autoconfiguration fetches and caches Google's published JWK set and
 * validates the token's expiry too. This class adds the two checks that
 * autoconfiguration cannot know about: the OIDC nonce (app-specific, tied to
 * one sign-in attempt) and Google's {@code email_verified} claim.
 */
@Service
public class GoogleIdTokenService {

    private final JwtDecoder jwtDecoder;

    public GoogleIdTokenService(
            JwtDecoder jwtDecoder,
            @Value("${spring.security.oauth2.resourceserver.jwt.audiences:}") String configuredAudiences,
            Environment environment) {
        this.jwtDecoder = jwtDecoder;
        boolean isTestProfile = Arrays.asList(environment.getActiveProfiles()).contains("test");
        if (!isTestProfile && (configuredAudiences == null || configuredAudiences.isBlank())) {
            // Spring's autoconfigured audience validator only activates when
            // `audiences` is non-empty. Left unset, a Google ID token minted
            // for ANY client would verify successfully here — silently
            // reintroducing the exact "believe the frontend" failure this
            // service exists to close. Fail the deploy, not a login attempt.
            throw new IllegalStateException(
                    "GOOGLE_CLIENT_ID is not set. Refusing to start: without it, Google ID token "
                            + "audience verification (spring.security.oauth2.resourceserver.jwt.audiences) "
                            + "is silently skipped, not merely permissive.");
        }
    }

    /**
     * @param idToken the raw JWT from Google Identity Services.
     * @param expectedNonce the value read from this browser's {@code coc_oidc_nonce}
     *     cookie — never from anything the request body claims.
     * @throws AuthenticationFailedException on any failed check. The reason
     *     string is for the audit log; see that exception's Javadoc for why
     *     it must never reach the HTTP response.
     */
    public VerifiedGoogleIdentity verify(String idToken, String expectedNonce) {
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
        return new VerifiedGoogleIdentity(email, (name == null || name.isBlank()) ? email : name);
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
