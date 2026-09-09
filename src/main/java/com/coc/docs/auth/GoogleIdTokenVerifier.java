package com.coc.docs.auth;

import com.coc.docs.config.AppProperties;
import java.util.List;
import java.util.Optional;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;

/**
 * Verifies a Google ID token server-side and extracts the identity from it.
 *
 * <p>The frontend never asserts who the caller is; it forwards a token Google
 * issued, and this class decides whether to believe it. Signature, issuer,
 * audience, expiry and {@code email_verified} are all checked here. Skipping any
 * of them turns sign-in into "claim to be anyone", which is TM-001.
 *
 * <p><b>Why the decoder is built by hand.</b> {@code issuer-uri} configuration
 * would make Spring fetch Google's OpenID discovery document during startup, so
 * the application would not boot without outbound internet — including in tests
 * and during a Flyway-only run. Pointing straight at the JWK set URI keeps
 * startup local; the key fetch then happens on the first sign-in and is cached.
 *
 * <p>// req: FR-AUTH-001, FR-AUTH-002, NFR-SEC-AUTH, ADR-008, ADR-012
 */
@Component
public class GoogleIdTokenVerifier {

    /**
     * Google's signing keys. Fixed by Google, not a deployment choice, so it is
     * a constant rather than configuration nobody would ever change.
     */
    static final String GOOGLE_JWK_SET_URI = "https://www.googleapis.com/oauth2/v3/certs";

    /**
     * Google issues ID tokens with either form of the issuer claim, and both are
     * legitimate. Accepting only one would reject valid tokens intermittently.
     */
    static final List<String> GOOGLE_ISSUERS =
            List.of("https://accounts.google.com", "accounts.google.com");

    private final AppProperties properties;

    /**
     * Built on first use, not in the constructor, so that a deployment without
     * Google credentials still starts and serves the reader path.
     */
    private volatile NimbusJwtDecoder decoder;

    public GoogleIdTokenVerifier(AppProperties properties) {
        this.properties = properties;
    }

    /**
     * A verified Google identity.
     *
     * @param subject Google's stable user id. Not used as the account key --
     *     accounts are keyed by email because that is what an administrator
     *     grants a role to -- but recorded so a later email change is traceable.
     */
    public record GoogleIdentity(String subject, String email, String displayName) {}

    /**
     * @throws AuthenticationFailure when the token is absent, malformed, expired,
     *     signed by the wrong key, addressed to another audience, issued by
     *     someone other than Google, or carries an unverified email
     */
    public GoogleIdentity verify(String idToken) {
        if (!properties.googleSignInConfigured()) {
            // Not the caller's fault and not a credential problem: the deployment
            // has no client id, so no token could ever be addressed to it.
            throw new AuthenticationFailure("Google sign-in is not configured on this deployment");
        }
        if (idToken == null || idToken.isBlank()) {
            throw new AuthenticationFailure("no ID token supplied");
        }

        Jwt jwt;
        try {
            jwt = decoder().decode(idToken);
        } catch (JwtException e) {
            // The reason is logged by the caller against the audit trail; the
            // client is told only that it failed. Telling a caller which check
            // failed is free reconnaissance.
            throw new AuthenticationFailure("ID token rejected: " + e.getMessage(), e);
        }

        String email = jwt.getClaimAsString("email");
        if (email == null || email.isBlank()) {
            throw new AuthenticationFailure("ID token carries no email claim");
        }
        // An unverified address could belong to someone else entirely, and
        // accounts here are keyed by email.
        if (!Boolean.TRUE.equals(jwt.getClaimAsBoolean("email_verified"))) {
            throw new AuthenticationFailure("Google has not verified this email address");
        }

        String name = jwt.getClaimAsString("name");
        return new GoogleIdentity(
                jwt.getSubject(),
                email,
                name == null || name.isBlank() ? email : name);
    }

    private NimbusJwtDecoder decoder() {
        NimbusJwtDecoder local = decoder;
        if (local == null) {
            synchronized (this) {
                local = decoder;
                if (local == null) {
                    local = build();
                    decoder = local;
                }
            }
        }
        return local;
    }

    private NimbusJwtDecoder build() {
        NimbusJwtDecoder built = NimbusJwtDecoder.withJwkSetUri(GOOGLE_JWK_SET_URI).build();

        OAuth2TokenValidator<Jwt> issuer = new JwtClaimValidator<String>(
                JwtClaimNames.ISS, claim -> claim != null && GOOGLE_ISSUERS.contains(claim));

        // Audience is the check that stops a token minted for a different Google
        // application from being replayed at this one.
        OAuth2TokenValidator<Jwt> audience = new JwtClaimValidator<List<String>>(
                JwtClaimNames.AUD,
                claim -> claim != null && claim.contains(properties.googleClientId()));

        built.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                new JwtTimestampValidator(), issuer, audience));
        return built;
    }

    /** Signals that a credential was not accepted. Mapped to 401 by the web layer. */
    public static class AuthenticationFailure extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public AuthenticationFailure(String message) {
            super(message);
        }

        public AuthenticationFailure(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** Exposed for the sign-in flow, which records the configured audience in audit detail. */
    public Optional<String> configuredAudience() {
        return properties.googleSignInConfigured()
                ? Optional.of(properties.googleClientId())
                : Optional.empty();
    }
}
