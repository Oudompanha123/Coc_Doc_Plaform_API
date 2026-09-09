package com.coc.docs.auth;

import com.coc.docs.account.Account;
import com.coc.docs.account.AccountRepository;
import com.coc.docs.account.AppRole;
import com.coc.docs.audit.AuditAction;
import com.coc.docs.audit.AuditRepository;
import com.coc.docs.config.AppProperties;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Issues, resolves and revokes sessions.
 *
 * <p>This is the chain of custody ADR-011 describes: Google verifies the
 * identity, this class issues a credential of its own bound to an account row,
 * and every later request is authorised against the role read from that row.
 * Nothing the client sends contributes to the role.
 *
 * <p>// req: FR-AUTH-001, FR-AUTH-004, FR-AUTH-005, FR-AUTH-007, NFR-SEC-SESSION
 */
@Service
public class SessionService {

    /**
     * 32 bytes of {@link SecureRandom}, base64url-encoded. Opaque and
     * unguessable; the token carries no claims, so there is nothing in it to
     * tamper with and no signature to verify.
     */
    private static final int TOKEN_BYTES = 32;

    public static final String COOKIE_NAME = "coc_session";

    private final SessionRepository sessions;
    private final AccountRepository accounts;
    private final AuditRepository audit;
    private final GoogleIdTokenVerifier google;
    private final AppProperties properties;
    private final ObjectMapper json;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public SessionService(
            SessionRepository sessions,
            AccountRepository accounts,
            AuditRepository audit,
            GoogleIdTokenVerifier google,
            AppProperties properties,
            ObjectMapper json,
            Clock clock) {
        this.sessions = sessions;
        this.accounts = accounts;
        this.audit = audit;
        this.google = google;
        this.properties = properties;
        this.json = json;
        this.clock = clock;
    }

    /** An issued session: the plaintext token, which exists only here and in the response. */
    public record IssuedSession(String token, Account account, Instant expiresAt) {}

    // -----------------------------------------------------------------------
    // Sign in
    // -----------------------------------------------------------------------

    /**
     * Verifies a Google ID token and issues a session.
     *
     * <p>A first-time signer-in gets an account at role {@code user} — read
     * access and nothing else. The role is never taken from the sign-in, so
     * signing in cannot be a route to privilege.
     *
     * <p>A deactivated account is refused. Deactivation has to end access
     * immediately, or it is a note in a table rather than a control.
     *
     * <p>// req: FR-AUTH-001, FR-AUTH-003, FR-AUTH-005
     */
    @Transactional
    public IssuedSession signIn(String idToken, String userAgent, String ipAddress) {
        GoogleIdTokenVerifier.GoogleIdentity identity;
        try {
            identity = google.verify(idToken);
        } catch (GoogleIdTokenVerifier.AuthenticationFailure e) {
            // Recorded without an actor id, because there is no verified actor.
            ObjectNode detail = json.createObjectNode().put("reason", e.getMessage());
            audit.append(
                    null, "unauthenticated", AuditAction.AUTH_REJECTED,
                    AuditAction.TARGET_SESSION, null, detail);
            throw e;
        }

        Account account = accounts
                .findByEmail(identity.email())
                .map(existing -> refreshName(existing, identity.displayName()))
                .orElseGet(() -> createAccount(identity));

        if (!account.isActive()) {
            throw new GoogleIdTokenVerifier.AuthenticationFailure("this account is deactivated");
        }

        Instant now = clock.instant();
        Instant expiresAt = now.plus(properties.sessionTtl());

        String token = newToken();
        sessions.insert(hash(token), account.id(), expiresAt, userAgent, ipAddress);

        audit.append(
                account.id(),
                account.email(),
                AuditAction.AUTH_SIGN_IN,
                AuditAction.TARGET_SESSION,
                account.id(),
                json.createObjectNode()
                        .put("role", account.role().wire())
                        .put("expiresAt", expiresAt.toString()));

        return new IssuedSession(token, account, expiresAt);
    }

    private Account refreshName(Account existing, String displayName) {
        if (!existing.displayName().equals(displayName)) {
            accounts.updateDisplayName(existing.id(), displayName);
            return new Account(
                    existing.id(),
                    existing.email(),
                    displayName,
                    existing.role(),
                    existing.deactivatedAt(),
                    existing.createdAt(),
                    existing.updatedAt());
        }
        return existing;
    }

    private Account createAccount(GoogleIdTokenVerifier.GoogleIdentity identity) {
        Account created = accounts.insert(identity.email(), identity.displayName(), AppRole.USER);
        audit.append(
                created.id(),
                created.email(),
                AuditAction.ACCOUNT_CREATE,
                AuditAction.TARGET_ACCOUNT,
                created.id(),
                json.createObjectNode()
                        .put("role", created.role().wire())
                        .put("via", "google-sign-in")
                        .put("googleSubject", identity.subject()));
        return created;
    }

    // -----------------------------------------------------------------------
    // Resolve
    // -----------------------------------------------------------------------

    /**
     * Resolves a presented token to the caller, or empty if it is not usable.
     *
     * <p>The account — and therefore the role — is read on every request rather
     * than cached in the session row. A role change or a deactivation then takes
     * effect on the caller's next request, not when their session happens to
     * expire.
     *
     * <p>// req: FR-AUTH-005, TM-003
     */
    @Transactional(readOnly = true)
    public Optional<CurrentUser> resolve(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        byte[] tokenHash = hash(token);

        Optional<AppSession> found = sessions.find(tokenHash);
        if (found.isEmpty() || !found.get().isLive(clock.instant())) {
            return Optional.empty();
        }

        return accounts
                .findById(found.get().accountId())
                .filter(Account::isActive)
                .map(account -> new CurrentUser(account, tokenHash));
    }

    // -----------------------------------------------------------------------
    // Sign out
    // -----------------------------------------------------------------------

    /**
     * Revokes a session server-side.
     *
     * <p>// req: FR-AUTH-007
     */
    @Transactional
    public void signOut(CurrentUser caller) {
        sessions.revoke(caller.sessionTokenHash());
        audit.append(
                caller.account().id(),
                caller.account().email(),
                AuditAction.AUTH_SIGN_OUT,
                AuditAction.TARGET_SESSION,
                caller.account().id(),
                json.createObjectNode());
    }

    /** Ends every session for an account, used when it is demoted or deactivated. */
    @Transactional
    public int revokeAllFor(java.util.UUID accountId) {
        return sessions.revokeAllFor(accountId);
    }

    /**
     * Removes session rows whose expiry is well past.
     *
     * <p>Retention is one TTL beyond expiry, so a row survives long enough to
     * answer "was this session already dead?" during an incident review, and no
     * longer than it has to.
     */
    @Transactional
    public int purgeExpired() {
        return sessions.deleteExpiredBefore(clock.instant().minus(properties.sessionTtl()));
    }

    // -----------------------------------------------------------------------
    // Token handling
    // -----------------------------------------------------------------------

    private String newToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * SHA-256 of the token, which is what the database holds.
     *
     * <p>No salt and no work factor, deliberately, and this is not the mistake it
     * resembles. A password needs both because it is low-entropy and chosen by a
     * human; this token is 256 bits from {@link SecureRandom}, so a lookup table
     * is not a threat and a slow hash would only tax the hot path of every
     * request. The hash exists so that a leaked database row is not a usable
     * credential.
     */
    static byte[] hash(String token) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required and absent from this JVM", e);
        }
    }
}
