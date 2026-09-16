package com.coc.docs.auth;

import java.time.Instant;
import java.util.UUID;

/**
 * One account's TOTP enrollment (ADR-031). At most one row per account
 * ({@code account_totp_account_uq}), whether pending or confirmed.
 *
 * <p>{@code secret} is plain text, not hashed — see {@code
 * V15__totp_mfa.sql}'s comment and ADR-031 Key Choice 2 for why a one-way
 * hash cannot be used here the way it is for {@code account.password_hash}.
 *
 * <p>// req: FR-AUTH-009, ADR-031
 */
public record AccountTotp(
        UUID id,
        UUID accountId,
        String secret,
        /** Null while a setup attempt is pending its confirmation code. */
        Instant confirmedAt,
        /** Anti-replay: the last 30-second step index a real code was accepted for. Null until first use. */
        Long lastUsedStep,
        Instant createdAt,
        Instant updatedAt) {

    /** Whether this enrollment is confirmed and therefore gates sign-in. */
    public boolean isConfirmed() {
        return confirmedAt != null;
    }
}
