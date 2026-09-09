package com.coc.docs.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * The {@code app.*} configuration block.
 *
 * <p>Bound and validated at startup, so a missing frontend origin fails the
 * application rather than surfacing later as CORS failing silently in a
 * browser, where the symptom is a blank page and the cause is invisible.
 *
 * @param sessionTtl session TTL. A single system-wide value: 1 day, per AMB-006
 *     and ADR-011. There is deliberately no per-role or "remember me" variant --
 *     NFR-SEC-SESSION asks for one defined value and one behaviour.
 * @param frontendOrigin the frontend origin, for CORS and cookie scope. Two
 *     services on different origins is an attack surface the single-service
 *     design did not have, so this is an allowlist of exactly one entry rather
 *     than a wildcard or a pattern.
 * @param cookieSecure true when the backend is served over TLS, which is what
 *     lets the session cookie carry {@code Secure}. False only for local
 *     development over plain HTTP: with the flag set, a browser discards the
 *     cookie on an http origin and sign-in appears to succeed and then does
 *     nothing at all.
 * @param googleClientId the Google OAuth client id that ID tokens must be
 *     addressed to. Blank is allowed -- see {@link #googleSignInConfigured()}.
 * @param storage object storage behind the S3 API
 *
 * <p>// req: CONST-TECH-08, NFR-SEC-SESSION, NFR-SEC-CHANNEL, ADR-011, ADR-012
 */
@Validated
@ConfigurationProperties(prefix = "app")
public record AppProperties(
        @NotNull Duration sessionTtl,
        @NotBlank String frontendOrigin,
        boolean cookieSecure,
        String googleClientId,
        @NotNull Storage storage) {

    /**
     * Object storage behind the S3 API.
     *
     * <p>The provider is deferred (ADR-012 decision 2) but the abstraction is
     * not, so nothing here names one.
     *
     * @param endpoint S3-compatible endpoint; empty until hosting is chosen
     * @param bucket bucket name
     * @param region region, which some S3 implementations require even when it
     *     is meaningless to them
     */
    public record Storage(String endpoint, String bucket, String region) {}

    /**
     * Whether Google sign-in is configured.
     *
     * <p>The application starts without a client id so that the reader path,
     * Flyway migrations and the whole test suite do not require Google
     * credentials. Sign-in then refuses with a clear message instead of the
     * application failing to boot, which is the difference between a deployment
     * that is partly usable and one that is not usable at all.
     *
     * <p>// req: FR-AUTH-001
     */
    public boolean googleSignInConfigured() {
        return googleClientId != null && !googleClientId.isBlank();
    }
}
