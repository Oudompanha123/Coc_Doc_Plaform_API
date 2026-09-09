package com.coc.docs.auth;

import java.time.Instant;
import java.util.UUID;

/**
 * A live server-side session.
 *
 * <p>Note what is absent: the token. Only its SHA-256 hash is ever stored or
 * read back, so a database dump yields nothing an attacker can present. The
 * plaintext token exists once, in the response that sets the cookie, and is
 * never recoverable afterwards.
 *
 * <p>// req: FR-AUTH-004, FR-AUTH-007, NFR-SEC-SESSION
 */
public record AppSession(
        byte[] tokenHash,
        UUID accountId,
        Instant issuedAt,
        Instant expiresAt,
        Instant revokedAt) {

    public boolean isLive(Instant now) {
        return revokedAt == null && now.isBefore(expiresAt);
    }
}
