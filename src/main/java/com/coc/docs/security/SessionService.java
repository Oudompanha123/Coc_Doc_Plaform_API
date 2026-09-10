package com.coc.docs.security;

import com.coc.docs.account.Account;
import com.coc.docs.account.AccountRepository;
import com.coc.docs.config.AppProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Issues, validates and revokes backend sessions (ADR-011 §2). The cookie
 * only ever carries a high-entropy opaque token; only its SHA-256 hash is
 * persisted, so a compromised database read cannot be replayed as a cookie.
 *
 * <p>Lives in {@code security}, not {@code auth}: this is generic session
 * infrastructure that any future authenticated endpoint relies on (via
 * {@link SessionCookieAuthenticationFilter}), not something specific to the
 * Google sign-in use case that {@code auth} implements on top of it.
 */
@Service
public class SessionService {

    private static final int TOKEN_BYTES = 32;

    private final AuthSessionRepository sessionRepository;
    private final AccountRepository accountRepository;
    private final AppProperties appProperties;
    private final SecureRandom random = new SecureRandom();

    SessionService(AuthSessionRepository sessionRepository, AccountRepository accountRepository,
            AppProperties appProperties) {
        this.sessionRepository = sessionRepository;
        this.accountRepository = accountRepository;
        this.appProperties = appProperties;
    }

    /**
     * Mints a new session for {@code accountId} and returns the raw cookie
     * value. This is the only moment the raw token exists outside the
     * browser's cookie jar — the database only ever sees its hash.
     */
    public String issue(UUID accountId) {
        String rawToken = newOpaqueToken();
        Instant expiresAt = Instant.now().plus(appProperties.sessionTtl());
        sessionRepository.insert(hash(rawToken), accountId, expiresAt);
        return rawToken;
    }

    /**
     * Resolves a raw cookie value to the account it belongs to, or empty if
     * the token is missing, unknown, expired, revoked, or the account can no
     * longer sign in (deactivated, or — ADR-014 — no longer {@code active}
     * by status, though in practice a session is never issued to a
     * non-active account in the first place; this is a defence-in-depth
     * check for a status change after the fact).
     */
    public Optional<Account> validate(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return Optional.empty();
        }
        String tokenHash = hash(rawToken);
        Optional<UUID> accountId = sessionRepository.findActiveAccountId(tokenHash);
        if (accountId.isEmpty()) {
            return Optional.empty();
        }
        sessionRepository.touch(tokenHash);
        return accountRepository.findById(accountId.get()).filter(Account::canSignIn);
    }

    /**
     * Deletes the session row outright — a real server-side revocation, not
     * a client-side cookie clear (ADR-011 §2). The very next request bearing
     * this cookie fails {@link #validate(String)}. // req: FR-AUTH-007
     */
    public void invalidate(String rawToken) {
        if (rawToken != null && !rawToken.isBlank()) {
            sessionRepository.delete(hash(rawToken));
        }
    }

    private String newOpaqueToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String hash(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(rawToken.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is a mandatory JDK algorithm; this cannot happen.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
