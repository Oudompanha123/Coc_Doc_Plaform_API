package com.coc.docs.auth;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import org.springframework.stereotype.Service;

/**
 * Mints the one-time OIDC nonce that ties a sign-in to the browser that started
 * it.
 *
 * <p><b>What this defends against.</b> Without a nonce, an ID token captured
 * anywhere — a shared machine, a proxy log, another application in the same
 * Keycloak realm — can be replayed at this backend by anyone holding it, and it
 * verifies perfectly: correct signature, correct issuer, correct audience, not
 * expired. The nonce is what makes a token usable only by the browser that
 * asked for it (TM-001).
 *
 * <p><b>Why it is a cookie and not a field in the request body.</b> The value
 * the backend compares against has to be one the caller cannot choose. A nonce
 * echoed back in the JSON body proves nothing — a replayer sends the captured
 * token and the nonce baked into it, and both match. Kept as an
 * {@code httpOnly} cookie, the expected value is something the browser holds
 * and script cannot read or set, so the comparison is between a token claim and
 * a server-issued secret the caller never saw.
 *
 * <p>// req: FR-AUTH-001, NFR-SEC-AUTH, TM-001, ADR-013
 */
@Service
public class NonceService {

    /** The cookie the ID token's {@code nonce} claim is checked against. */
    public static final String COOKIE_NAME = "coc_oidc_nonce";

    /**
     * Long enough to sign in with, short enough that a captured cookie is
     * worth little. Five minutes covers a Google consent screen, including
     * picking an account and a password manager prompt; it does not cover
     * walking away from the desk.
     */
    public static final Duration TTL = Duration.ofMinutes(5);

    /** 32 bytes of {@link SecureRandom}. Guessing is not a threat model at this width. */
    private static final int NONCE_BYTES = 32;

    private final SecureRandom random = new SecureRandom();

    public String newNonce() {
        byte[] bytes = new byte[NONCE_BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
