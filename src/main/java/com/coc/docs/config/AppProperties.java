package com.coc.docs.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
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
 * @param googleClientSecret the same client's secret, needed <em>only</em> to
 *     exchange an authorization code for a Gmail refresh token (ADR-085).
 *     Sign-in does not use it and never will: verifying an ID token is done
 *     with Google's public keys, so a deployment that wants sign-in and not
 *     Gmail access leaves this blank and loses nothing.
 * @param storage object storage behind the S3 API
 * @param corsAllowedOriginPatterns extra origins allowed by CORS on top of
 *     {@code frontendOrigin}, as Spring origin patterns ({@code *} matches
 *     within a host). Empty by default, which keeps the allowlist at exactly
 *     one entry. It exists for hosts that give every deployment its own URL
 *     (Vercel previews). A pattern must end in a suffix only this team can
 *     deploy under: credentials are allowed, so a pattern anyone can register
 *     a matching host for hands them a signed-in session.
 * @param cookieSameSite the {@code SameSite} attribute of every cookie the
 *     API sets: {@code Lax} (default), {@code Strict} or {@code None}.
 *     {@code None} is needed when the frontend and the API are on different
 *     sites (for example {@code vercel.app} and {@code onrender.com}), where
 *     a browser otherwise refuses to store the session cookie and sign-in
 *     succeeds and then does nothing. It requires {@code cookieSecure}.
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
        String googleClientSecret,
        @NotNull Storage storage,
        List<String> corsAllowedOriginPatterns,
        String cookieSameSite) {

    /** Normalizes the two optional settings and refuses a combination a browser would reject. */
    @ConstructorBinding
    public AppProperties {
        corsAllowedOriginPatterns = corsAllowedOriginPatterns == null
                ? List.of()
                : corsAllowedOriginPatterns.stream()
                        .map(String::trim)
                        .filter(pattern -> !pattern.isEmpty())
                        .toList();
        cookieSameSite = canonicalSameSite(cookieSameSite);
        if ("None".equals(cookieSameSite) && !cookieSecure) {
            throw new IllegalArgumentException(
                    "app.cookie-same-site=None requires app.cookie-secure=true: "
                            + "browsers discard a SameSite=None cookie that is not Secure");
        }
    }

    /** The single-site shape: no extra CORS origins, {@code SameSite=Lax}. */
    public AppProperties(
            Duration sessionTtl,
            String frontendOrigin,
            boolean cookieSecure,
            String googleClientId,
            String googleClientSecret,
            Storage storage) {
        this(sessionTtl, frontendOrigin, cookieSecure, googleClientId, googleClientSecret, storage, List.of(), "Lax");
    }

    private static String canonicalSameSite(String value) {
        if (value == null || value.isBlank()) {
            return "Lax";
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "lax" -> "Lax";
            case "strict" -> "Strict";
            case "none" -> "None";
            default -> throw new IllegalArgumentException(
                    "app.cookie-same-site must be Lax, Strict or None, not '" + value + "'");
        };
    }

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
     * @param accessKeyId S3-compatible access key; blank until hosting is
     *     chosen, same convention as {@link #googleClientId}
     * @param secretAccessKey the matching secret. Never logged, never
     *     returned in any response — same handling as {@code DB_PASSWORD}.
     */
    public record Storage(
            String endpoint, String bucket, String region, String accessKeyId, String secretAccessKey) {

        /**
         * Whether manual avatar upload (ADR-026) can actually work on this
         * deployment. {@code AvatarStorageConfig} registers no {@code
         * AvatarStorage} bean at all when this is false, so the feature is
         * cleanly absent rather than failing at the moment someone tries it.
         */
        public boolean configured() {
            return endpoint != null && !endpoint.isBlank()
                    && bucket != null && !bucket.isBlank()
                    && accessKeyId != null && !accessKeyId.isBlank()
                    && secretAccessKey != null && !secretAccessKey.isBlank();
        }

        /**
         * Whether an endpoint and bucket are set, keys or not. The Store's S3
         * backend (ADR-115) also runs with no keys at all, on an EC2 instance
         * role, which {@link #configured()} would call "not configured".
         */
        public boolean bucketConfigured() {
            return endpoint != null && !endpoint.isBlank() && bucket != null && !bucket.isBlank();
        }

        /** Both keys set — otherwise the AWS default credential chain (instance role) is used. */
        public boolean hasStaticKeys() {
            return accessKeyId != null && !accessKeyId.isBlank()
                    && secretAccessKey != null && !secretAccessKey.isBlank();
        }
    }

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
