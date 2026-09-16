package com.coc.docs.auth;

import java.time.Instant;
import java.util.UUID;

/**
 * A password sign-in that has passed its first factor and is waiting on a
 * TOTP code (ADR-031). Mirrors {@link RegistrationChallenge}'s shape: an
 * opaque, hashed-at-rest, single-use token with a short TTL.
 *
 * <p>// req: FR-AUTH-009, ADR-031
 */
public record MfaLoginChallenge(UUID id, UUID accountId, byte[] tokenHash, Instant issuedAt, Instant expiresAt) {

    /** Presentable now: not yet past its 5-minute window. */
    public boolean isUsable(Instant now) {
        return now.isBefore(expiresAt);
    }
}
