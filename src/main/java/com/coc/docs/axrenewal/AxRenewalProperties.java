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
 * <p>There is deliberately no {@code sourcePath} here (ADR-052 revises
 * ADR-043/051). A fixed, server-side default for "the attached project's
 * folder on this machine" turned one person's own checkout into every
 * caller's implicit answer — wrong the moment somebody else's checkout lives
 * somewhere else, or the same person is working on a second legacy project.
 * Run analysis, Start implementation and the assistant chat each now take a
 * {@code sourcePath} argument instead, supplied per call and validated by
 * {@code AxRenewalService} before any of them ever reads it — see {@link
 * LocalClaudeAnalyzer#analyse}, {@link AxRenewalCodeGenerator#generate}, and
 * {@link LocalClaudeChat#reply}.
 *
 * @param anthropicApiKey a paid API key for a server-side call. Still unread
 *     by any code path — reserved for the phase where the deployed site can
 *     analyse for everybody (ADR-037 §4.3).
 * @param claudeCliPath absolute path to a local Claude Code binary. When set,
 *     {@link AxRenewalService#runAnalysis} asks it to read the project instead
 *     of keyword-matching. See {@link LocalClaudeAnalyzer} for why this can
 *     only ever be a local-development capability.
 */
@ConfigurationProperties(prefix = "app.ax-renewal")
public record AxRenewalProperties(String anthropicApiKey, String claudeCliPath) {

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
}
