package com.coc.docs.claudeusage;

import java.time.Instant;
import java.util.UUID;

/**
 * A {@code claude_usage_ingest_token} row (V35) — a per-account bearer
 * credential for {@code POST /api/ingest}, alongside the shared secret
 * {@code ClaudeUsageProperties} still supports.
 *
 * <p>Only {@link #tokenHash} is ever stored; the raw {@code coc_...} value is
 * returned to its owner once, at creation, by {@link ClaudeUsageIngestTokenService#create},
 * and is not recoverable from this row afterwards — same shape {@link
 * com.coc.docs.auth.PasswordResetToken} already uses for the same reason.
 *
 * <p>// req: FR-USAGE-002, NFR-SEC-CHANNEL, ADR-044
 */
public record ClaudeUsageIngestToken(
        UUID id, UUID accountId, String tokenHash, String label, Instant createdAt, Instant lastUsedAt, Instant revokedAt) {

    /** {@code revoked_at is null} — the same test the table's own partial index is built on. */
    public boolean isActive() {
        return revokedAt == null;
    }
}
