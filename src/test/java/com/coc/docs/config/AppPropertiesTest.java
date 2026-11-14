package com.coc.docs.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/** The two cross-site settings: their defaults, their binding, and the combination that is refused. */
class AppPropertiesTest {

    private static AppProperties bind(Map<String, String> extra) {
        Map<String, String> values = new java.util.HashMap<>(Map.of(
                "app.session-ttl", "P1D",
                "app.frontend-origin", "https://docs.example.com",
                "app.cookie-secure", "true",
                "app.storage.bucket", "b"));
        values.putAll(extra);
        return new Binder(new MapConfigurationPropertySource(values))
                .bind("app", AppProperties.class)
                .get();
    }

    @Test
    void defaultsToOneOriginAndLax() {
        AppProperties properties = bind(Map.of("app.cors-allowed-origin-patterns", "", "app.cookie-same-site", ""));

        assertThat(properties.corsAllowedOriginPatterns()).isEmpty();
        assertThat(properties.cookieSameSite()).isEqualTo("Lax");
    }

    @Test
    void bindsCommaSeparatedPatternsAndNormalizesSameSite() {
        AppProperties properties = bind(Map.of(
                "app.cors-allowed-origin-patterns", "https://app-*-team.vercel.app, https://other.example.com",
                "app.cookie-same-site", "none"));

        assertThat(properties.corsAllowedOriginPatterns())
                .containsExactly("https://app-*-team.vercel.app", "https://other.example.com");
        assertThat(properties.cookieSameSite()).isEqualTo("None");
    }

    @Test
    void refusesSameSiteNoneWithoutSecure() {
        assertThatThrownBy(() -> bind(Map.of("app.cookie-secure", "false", "app.cookie-same-site", "None")))
                .hasStackTraceContaining("requires app.cookie-secure=true");
    }

    @Test
    void refusesAnUnknownSameSiteValue() {
        assertThatThrownBy(() -> bind(Map.of("app.cookie-same-site", "Loose")))
                .hasStackTraceContaining("must be Lax, Strict or None");
    }
}
