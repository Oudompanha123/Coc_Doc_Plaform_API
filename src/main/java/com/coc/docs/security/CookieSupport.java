package com.coc.docs.security;

import com.coc.docs.config.AppProperties;
import java.time.Duration;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * Builds every {@code Set-Cookie} header this API issues (the nonce cookie
 * and the session cookie), so the {@code httpOnly}/{@code secure}/{@code sameSite}
 * attributes NFR-SEC-SESSION requires are applied in exactly one place.
 */
@Component
public class CookieSupport {

    private final AppProperties appProperties;

    public CookieSupport(AppProperties appProperties) {
        this.appProperties = appProperties;
    }

    public ResponseCookie build(String name, String value, Duration ttl, String path) {
        return base(name, value, path).maxAge(ttl).build();
    }

    /** A cookie that tells the browser to delete it immediately. */
    public ResponseCookie clear(String name, String path) {
        return base(name, "", path).maxAge(Duration.ZERO).build();
    }

    private ResponseCookie.ResponseCookieBuilder base(String name, String value, String path) {
        ResponseCookie.ResponseCookieBuilder builder = ResponseCookie.from(name, value)
                // Not readable by JS regardless of which origin issued it —
                // the main XSS-to-session-theft path (TM-002).
                .httpOnly(true)
                .secure(appProperties.cookie().secure())
                .sameSite(appProperties.cookie().sameSite())
                .path(path);
        String domain = appProperties.cookie().domain();
        if (domain != null && !domain.isBlank()) {
            builder = builder.domain(domain);
        }
        return builder;
    }
}
