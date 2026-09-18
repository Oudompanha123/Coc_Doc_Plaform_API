package com.coc.docs.axrenewal;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The {@code app.ax-renewal.*} configuration block.
 *
 * <p>Stubbed for Phase 2 (ADR-037) and genuinely unread by any Phase-1 code
 * path — {@link AxRenewalService#runAnalysis} never branches on {@link
 * #anthropicConfigured()}. Follows {@code ClaudeUsageProperties}'s pattern
 * exactly: unvalidated, so the application boots with nothing configured, and
 * {@code anthropicApiKey} is never logged and never appears in any API
 * response.
 */
@ConfigurationProperties(prefix = "app.ax-renewal")
public record AxRenewalProperties(String anthropicApiKey) {

    public boolean anthropicConfigured() {
        return anthropicApiKey != null && !anthropicApiKey.isBlank();
    }
}
