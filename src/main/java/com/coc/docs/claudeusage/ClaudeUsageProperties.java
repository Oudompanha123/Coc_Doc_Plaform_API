package com.coc.docs.claudeusage;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The {@code app.claude-usage.*} configuration block.
 *
 * <p>Deliberately outside {@link com.coc.docs.config.AppProperties} and
 * carries no {@code @Validated}/{@code @NotBlank}: this project's other secret
 * ({@code AppProperties.Storage}) is a {@code @NotNull} nested record, which
 * only binds because {@code application.yml} already declares its (blank)
 * keys. Adding a second such record would require editing that file, which
 * this backend's own operational rule (`CONST-OPS-01`'s neighbours — no
 * secret/config file is read or written by an automated change here) puts out
 * of reach. An unvalidated {@code @ConfigurationProperties} class needs no
 * entry to exist anywhere: with nothing configured, {@link #ingestSecret()} is
 * simply null and {@link #ingestConfigured()} says so, the same "cleanly
 * absent, not a boot failure" shape {@code AppProperties.googleSignInConfigured()}
 * already uses.
 *
 * <p>{@code ingestSecret} is never logged and never appears in any API
 * response — same handling {@code AppProperties.Storage.secretAccessKey}
 * already documents for the same reason.
 *
 * <p>// req: FR-USAGE-002, NFR-SEC-CHANNEL, ADR-035
 */
@ConfigurationProperties(prefix = "app.claude-usage")
public record ClaudeUsageProperties(String ingestSecret) {

    /**
     * Whether {@code POST /api/ingest} can ever succeed on this deployment.
     *
     * <p>Deliberately mirrors {@code AppProperties.googleSignInConfigured()}:
     * the application starts and every other feature works with this unset,
     * and the endpoint refuses with a clear message rather than the whole
     * backend failing to boot over a feature nobody has configured yet.
     */
    public boolean ingestConfigured() {
        return ingestSecret != null && !ingestSecret.isBlank();
    }
}
