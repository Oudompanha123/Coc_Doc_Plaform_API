package com.coc.docs.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds the {@code app.*} tree in {@code application.yml}.
 *
 * <p>A record so every field is required-by-default and immutable once bound —
 * a missing {@code app.session-ttl} (say) fails at startup instead of quietly
 * running with a Java {@code null}.
 *
 * @param sessionTtl session TTL. AMB-006 resolved this to a single, defined
 *     value (NFR-SEC-SESSION); see {@code application.yml} for the history.
 * @param frontendOrigin comma-separated exact origin(s) allowed to make
 *     credentialed cross-origin calls. Never a wildcard or pattern —
 *     ADR-011 §2.5.
 * @param cookie session/nonce cookie attributes, tunable per environment
 *     without a code change (ADR-011 §2.5's D1/D2 choice is still open).
 * @param oidc OIDC-flow-specific settings (currently just the nonce TTL).
 */
@ConfigurationProperties(prefix = "app")
public record AppProperties(Duration sessionTtl, String frontendOrigin, Cookie cookie, Oidc oidc) {

    public record Cookie(String sameSite, boolean secure, String domain) {}

    public record Oidc(Duration nonceTtl) {}
}
