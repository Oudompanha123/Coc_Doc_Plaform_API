package com.coc.docs.axrenewal;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The {@code app.ax-renewal.*} configuration block.
 *
 * <p>Two independent ways to reach a model, neither required. Follows {@code
 * ClaudeUsageProperties}'s pattern exactly: unvalidated, so the application
 * boots with nothing configured, and no value here is ever logged or returned
 * in an API response.
 *
 * @param anthropicApiKey a paid API key for a server-side call. Still unread
 *     by any code path — reserved for the phase where the deployed site can
 *     analyse for everybody (ADR-037 §4.3).
 * @param claudeCliPath absolute path to a local Claude Code binary. When set,
 *     {@link AxRenewalService#runAnalysis} asks it to read the project instead
 *     of keyword-matching. See {@link LocalClaudeAnalyzer} for why this can
 *     only ever be a local-development capability.
 * @param sourcePath absolute path to the attached project's folder on this
 *     same machine. Optional even when {@code claudeCliPath} is set: without
 *     it the model reads the stored manifest only, which is a real reading of
 *     real content but not of the source. The browser cannot supply this —
 *     the File API deliberately hides absolute paths — so it is configuration,
 *     not something the attach step can fill in.
 */
@ConfigurationProperties(prefix = "app.ax-renewal")
public record AxRenewalProperties(String anthropicApiKey, String claudeCliPath, String sourcePath) {

    public boolean anthropicConfigured() {
        return anthropicApiKey != null && !anthropicApiKey.isBlank();
    }

    /**
     * Whether this machine can run analysis through a local Claude Code
     * installation.
     *
     * <p>Keyed on the path being <em>configured</em>, not on the file
     * existing: a deployment that has not set it must take the "off" branch
     * without touching the filesystem at all, and a developer who sets it to
     * something wrong deserves an error naming the path rather than a silent
     * fall back to the stub.
     */
    public boolean localClaudeConfigured() {
        return claudeCliPath != null && !claudeCliPath.isBlank();
    }

    /** Whether the model can be pointed at real source, not just the manifest. */
    public boolean sourcePathConfigured() {
        return sourcePath != null && !sourcePath.isBlank();
    }
}
