package com.coc.docs.web;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.util.ForwardedHeaderUtils;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * The absolute {@code /api/ingest} URL a downloaded {@code push-usage.mjs}
 * embeds as its {@code INGEST_URL} — decided here, by the deployment that is
 * actually serving the download, rather than by the frontend.
 *
 * <p>The frontend used to build it from {@code NEXT_PUBLIC_BACKEND_URL}, which
 * Next.js inlines at <i>build</i> time and which silently falls back to
 * {@code http://localhost:8080} when unset. A production bundle built without
 * that argument therefore handed every user a script that posted to their own
 * machine's localhost. The backend knows where it is reachable at request
 * time, so it is the one place this cannot drift.
 *
 * <p>Resolution order:
 * <ol>
 *   <li>{@code app.public-url} (env {@code APP_PUBLIC_URL}) — the public base
 *       URL of this deployment, e.g. {@code https://docs.example.com}. Set
 *       this in production; it is the only value that is never a guess.</li>
 *   <li>Otherwise, the scheme/host/port the caller reached this request on,
 *       adapted by {@code Forwarded}/{@code X-Forwarded-*} headers so a TLS-
 *       terminating proxy (infra/nginx sets {@code X-Forwarded-Proto}) yields
 *       {@code https://<domain>} rather than the proxy-to-backend hop. In local
 *       development that is {@code http://localhost:8080}, which is correct
 *       there. Those headers are client-influenced, but the only thing they can
 *       change is the URL embedded in the caller's <i>own</i> download.</li>
 * </ol>
 *
 * <p>// req: FR-USAGE-002, ADR-050
 */
@Component
public class PublicIngestUrl {

    static final String INGEST_PATH = "/api/ingest";

    /** Normalized (no trailing slash), or null when not configured. */
    private final String configuredBaseUrl;

    public PublicIngestUrl(@Value("${app.public-url:}") String configuredBaseUrl) {
        this.configuredBaseUrl = normalize(configuredBaseUrl);
    }

    public String forRequest(HttpServletRequest request) {
        if (configuredBaseUrl != null) {
            return configuredBaseUrl + INGEST_PATH;
        }
        URI requestUri = URI.create(request.getRequestURL().toString());
        return ForwardedHeaderUtils
                .adaptFromForwardedHeaders(requestUri, new ServletServerHttpRequest(request).getHeaders())
                .replacePath(request.getContextPath() + INGEST_PATH)
                .replaceQuery(null)
                .fragment(null)
                .build()
                .toUriString();
    }

    /**
     * Fails startup on a malformed value instead of shipping scripts that can
     * never reach the server — the same "refuse to boot rather than fail
     * invisibly later" stance {@code AppProperties} takes for its own origin.
     */
    static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String trimmed = raw.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        URI uri;
        try {
            uri = UriComponentsBuilder.fromUriString(trimmed).build(true).toUri();
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("app.public-url is not a valid URL: " + trimmed, e);
        }
        String scheme = uri.getScheme();
        if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) || uri.getHost() == null) {
            throw new IllegalStateException(
                    "app.public-url must be an absolute http(s) URL such as https://docs.example.com, got: " + trimmed);
        }
        if (uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalStateException("app.public-url must not carry a query or fragment: " + trimmed);
        }
        return trimmed;
    }
}
