package com.coc.docs.auth;

import java.time.Instant;
import java.util.UUID;

/**
 * A registration application waiting on its emailed 6-digit code (ADR-021,
 * revision of ADR-014/017). Never becomes an {@code account} row itself —
 * {@code AccountService.register} creates that, on a confirmed code.
 *
 * <p>{@code passwordHash} is already bcrypt-hashed, the same convention
 * {@code AccountService.register}'s javadoc states for {@code
 * account.password_hash} itself: hashing is the web layer's job, done once,
 * before either table ever sees the plaintext.
 *
 * <p>// req: ADR-021
 */
public record RegistrationChallenge(
        UUID id,
        String email,
        String displayName,
        String passwordHash,
        byte[] codeHash,
        int attempts,
        int resendCount,
        Instant issuedAt,
        Instant expiresAt) {

    /** Presentable now: not yet past its 10-minute window. */
    public boolean isUsable(Instant now) {
        return now.isBefore(expiresAt);
    }
}
