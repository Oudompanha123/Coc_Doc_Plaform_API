package com.coc.docs.config;

import com.coc.docs.auth.KeycloakIdTokenService;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * Wires Keycloak token verification, and only when a realm is configured.
 *
 * <p>Everything here is conditional on {@code app.keycloak.issuer-uri}. Without
 * it neither bean exists, the application boots exactly as it does today, and
 * nothing that does not sign in notices. Setting it is what turns ADR-013's
 * identity broker on.
 *
 * <p><b>Why the decoder is built here rather than autoconfigured.</b>
 * {@code spring.security.oauth2.resourceserver.jwt.issuer-uri} makes Spring
 * fetch the OpenID discovery document at startup, which means the application
 * cannot boot unless Keycloak is reachable — an unacceptable coupling for tests,
 * for a Flyway-only run, and for five developers whose container may not be up.
 * Pointing straight at the JWK set keeps startup local; keys are fetched on the
 * first sign-in and cached from then on.
 *
 * <p>// req: FR-AUTH-001, NFR-SEC-AUTH, ADR-013
 */
@Configuration
@ConditionalOnProperty(prefix = "app.keycloak", name = "issuer-uri")
public class KeycloakAuthConfig {

    /**
     * Signature, issuer, audience and expiry are all checked here. The two
     * checks this cannot make — the OIDC nonce, which belongs to one sign-in
     * attempt, and {@code email_verified} — are made by
     * {@link KeycloakIdTokenService}.
     */
    @Bean
    public JwtDecoder keycloakJwtDecoder(KeycloakProperties keycloak) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(keycloak.jwkSetUri()).build();

        OAuth2TokenValidator<Jwt> issuer = new JwtClaimValidator<String>(
                JwtClaimNames.ISS, claim -> claim != null && claim.equals(keycloak.issuerUri()));

        // The check that stops a token Keycloak minted for a different client in
        // the same realm from being replayed at this one.
        OAuth2TokenValidator<Jwt> audience = new JwtClaimValidator<List<String>>(
                JwtClaimNames.AUD,
                claim -> claim != null && keycloak.audience() != null && claim.contains(keycloak.audience()));

        decoder.setJwtValidator(
                new DelegatingOAuth2TokenValidator<>(new JwtTimestampValidator(), issuer, audience));
        return decoder;
    }

    /**
     * Declared as a bean rather than component-scanned, so that it exists only
     * alongside the decoder it needs. A {@code @Service} would be constructed
     * on every deployment, including those with no realm, and fail at startup
     * looking for a {@code JwtDecoder} that was never meant to exist.
     *
     * <p>The service's own constructor refuses to start when the audience is
     * blank. That is deliberate and is left intact: a configured realm with no
     * client id skips audience verification silently rather than permissively,
     * which is worse than not starting.
     */
    @Bean
    public KeycloakIdTokenService keycloakIdTokenService(
            JwtDecoder keycloakJwtDecoder, KeycloakProperties keycloak, Environment environment) {
        return new KeycloakIdTokenService(keycloakJwtDecoder, keycloak.audience(), environment);
    }
}
