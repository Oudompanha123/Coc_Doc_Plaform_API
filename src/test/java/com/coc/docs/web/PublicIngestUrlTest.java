package com.coc.docs.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * The {@code INGEST_URL} a personalized {@code push-usage.mjs} embeds must be
 * the deployment's own public URL — never a localhost fallback baked in at
 * frontend build time.
 *
 * <p>// req: FR-USAGE-002, ADR-050
 */
class PublicIngestUrlTest {

    private static MockHttpServletRequest request(String scheme, String host, int port) {
        MockHttpServletRequest r = new MockHttpServletRequest("POST", "/api/v1/me/usage-tokens");
        r.setScheme(scheme);
        r.setServerName(host);
        r.setServerPort(port);
        return r;
    }

    @Nested
    @DisplayName("APP_PUBLIC_URL configured")
    class Configured {

        @Test
        @DisplayName("wins over whatever host the request arrived on")
        void configuredWins() {
            PublicIngestUrl url = new PublicIngestUrl("https://docs.example.com");
            assertThat(url.forRequest(request("http", "localhost", 8080)))
                    .isEqualTo("https://docs.example.com/api/ingest");
        }

        @Test
        @DisplayName("trailing slashes are tolerated")
        void trailingSlash() {
            assertThat(new PublicIngestUrl("https://docs.example.com//").forRequest(request("http", "x", 80)))
                    .isEqualTo("https://docs.example.com/api/ingest");
        }

        @Test
        @DisplayName("a malformed value fails startup instead of shipping unreachable scripts")
        void malformedFails() {
            assertThatThrownBy(() -> new PublicIngestUrl("docs.example.com")).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> new PublicIngestUrl("ftp://docs.example.com")).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> new PublicIngestUrl("https://docs.example.com?x=1")).isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    @DisplayName("APP_PUBLIC_URL not configured — derived from the request")
    class Derived {

        private final PublicIngestUrl url = new PublicIngestUrl("");

        @Test
        @DisplayName("local development: http://localhost:8080/api/ingest")
        void local() {
            assertThat(url.forRequest(request("http", "localhost", 8080)))
                    .isEqualTo("http://localhost:8080/api/ingest");
        }

        @Test
        @DisplayName("behind the TLS-terminating nginx: https://<domain>/api/ingest, no localhost, no backend port")
        void behindProxy() {
            MockHttpServletRequest r = request("http", "docs.example.com", 80);
            r.addHeader("X-Forwarded-Proto", "https");

            String resolved = url.forRequest(r);

            assertThat(resolved).isEqualTo("https://docs.example.com/api/ingest");
            assertThat(resolved).doesNotContain("localhost");
        }

        @Test
        @DisplayName("never echoes the minting request's own path or query")
        void pathReplaced() {
            MockHttpServletRequest r = request("http", "localhost", 8080);
            r.setQueryString("a=b");
            assertThat(url.forRequest(r)).isEqualTo("http://localhost:8080/api/ingest");
        }
    }
}
