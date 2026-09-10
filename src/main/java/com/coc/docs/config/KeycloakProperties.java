package com.coc.docs.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where the Keycloak realm lives, and which client this application is.
 *
 * <p>Deliberately its own record rather than two more fields on
 * {@link AppProperties}: Keycloak is optional. A deployment that has not stood
 * one up yet — every developer machine until they run the container — must
 * still boot and serve the reader path. Bundling these into {@code AppProperties},
 * which is {@code @Validated}, would make an unset issuer a startup failure for
 * everyone.
 *
 * <p>Bound from {@code app.keycloak.*}. Picked up by the
 * {@code @ConfigurationPropertiesScan} on the application class.
 *
 * <p><b>Not {@code spring.security.oauth2.resourceserver.jwt.*}.</b> That key
 * makes Spring autoconfigure a {@code JwtDecoder} by fetching the issuer's
 * OpenID discovery document <em>during startup</em>, so the application would
 * not boot without Keycloak reachable — including in tests and on a Flyway-only
 * run. {@link KeycloakAuthConfig} builds the decoder from the JWK set URI
 * instead, which keeps startup local and defers the key fetch to the first
 * sign-in. This is the same reasoning, and the same shape, as
 * {@code GoogleIdTokenVerifier}.
 *
 * @param issuerUri the realm URL Keycloak puts in the {@code iss} claim, e.g.
 *     {@code https://keycloak.example.com/realms/coc}. Unset means Keycloak
 *     sign-in is simply not available on this deployment.
 * @param audience this application's Keycloak client id. Unset while
 *     {@code issuerUri} is set is a startup failure, not a default — see
 *     {@link com.coc.docs.auth.KeycloakIdTokenService}, which refuses to start
 *     rather than skip audience verification silently.
 *
 *     <p>// req: FR-AUTH-001, NFR-SEC-AUTH, ADR-013
 */
@ConfigurationProperties(prefix = "app.keycloak")
public record KeycloakProperties(String issuerUri, String audience) {

    /** True when this deployment has a realm to trust. */
    public boolean configured() {
        return issuerUri != null && !issuerUri.isBlank();
    }

    /**
     * Keycloak publishes its signing keys at a fixed path under the realm, so
     * this is derived rather than being a second thing to configure and keep
     * consistent.
     */
    public String jwkSetUri() {
        String base = issuerUri.endsWith("/") ? issuerUri.substring(0, issuerUri.length() - 1) : issuerUri;
        return base + "/protocol/openid-connect/certs";
    }
}
