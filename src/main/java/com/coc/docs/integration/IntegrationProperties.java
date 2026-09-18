package com.coc.docs.integration;

import java.util.Base64;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The {@code app.integrations.*} configuration block.
 *
 * <p>Deliberately outside {@link com.coc.docs.config.AppProperties}, unvalidated,
 * for exactly the reason {@link com.coc.docs.claudeusage.ClaudeUsageProperties}
 * gives for the same shape: this project's other secret record
 * ({@code AppProperties.Storage}) only binds because {@code application.yml}
 * already declares its (blank) keys, and this backend's own operational rule
 * (no secret/config file is read or written by an automated change) puts
 * editing that file out of reach here. With nothing configured,
 * {@link #encryptionKey()} is simply null and {@link #configured()} says so —
 * the same "cleanly absent, not a boot failure" shape every optional bean in
 * this codebase already uses.
 *
 * <p>{@code encryptionKey} is base64-encoded, 32 raw bytes (AES-256). Never
 * logged, never returned in any API response — same handling
 * {@code AppProperties.Storage.secretAccessKey} already documents.
 *
 * <p>// req: FR-INTEG-001, FR-INTEG-002, ADR-037
 */
@ConfigurationProperties(prefix = "app.integrations")
public record IntegrationProperties(String encryptionKey) {

    private static final int REQUIRED_KEY_BYTES = 32;

    /**
     * Whether {@code POST /api/v1/admin/integrations} (and every other
     * write that touches a signing secret) can succeed on this deployment.
     *
     * <p>Deliberately mirrors {@code ClaudeUsageProperties.ingestConfigured()}:
     * the application starts and every other feature works with this unset,
     * and the affected endpoints refuse with a clear message rather than the
     * whole backend failing to boot over a feature nobody has configured yet.
     */
    public boolean configured() {
        return decodedKeyOrNull() != null;
    }

    /**
     * The raw 32-byte AES-256 key, or {@code null} when unset or malformed.
     * Malformed (present but not valid base64, or not exactly 32 bytes) is
     * treated the same as absent — refusing the write is safer than guessing
     * at a key that was clearly meant to be something else.
     */
    byte[] decodedKeyOrNull() {
        if (encryptionKey == null || encryptionKey.isBlank()) {
            return null;
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(encryptionKey.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
        return decoded.length == REQUIRED_KEY_BYTES ? decoded : null;
    }
}
