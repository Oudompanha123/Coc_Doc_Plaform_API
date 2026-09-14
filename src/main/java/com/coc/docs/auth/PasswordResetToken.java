package com.coc.docs.auth;

import java.time.Instant;
import java.util.UUID;

/**
 * A password-reset token, hashed. Mirrors {@link AppSession}'s shape and the
 * reason for it: only the SHA-256 hash is ever stored or read back, so a
 * database dump yields nothing an attacker can present. The plaintext token
 * exists once, in the email, and is never recoverable afterwards.
 *
 * <p>// req: ADR-018
 */
public record PasswordResetToken(
        byte[] tokenHash, UUID accountId, Instant issuedAt, Instant expiresAt, Instant usedAt) {

    /** Presentable now: not already spent, and not past its 30-minute window. */
    public boolean isUsable(Instant now) {
        return usedAt == null && now.isBefore(expiresAt);
    }
}
