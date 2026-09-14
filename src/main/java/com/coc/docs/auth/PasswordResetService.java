package com.coc.docs.auth;

import com.coc.docs.account.Account;
import com.coc.docs.account.AccountCredential;
import com.coc.docs.account.AccountRepository;
import com.coc.docs.audit.AuditAction;
import com.coc.docs.audit.AuditRepository;
import com.coc.docs.web.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Mints and redeems password-reset tokens (ADR-018, revision of ADR-014's
 * "no password reset flow exists" gap).
 *
 * <p>// req: FR-AUTH-002, ADR-014, ADR-018
 */
@Service
public class PasswordResetService {

    /** Matches {@code NonceService}/{@code SessionService}: opaque, unguessable, no claims to verify. */
    private static final int TOKEN_BYTES = 32;

    /** Fixed, system-wide, no per-deployment variant — see ADR-018 decision 1. */
    public static final Duration TTL = Duration.ofMinutes(30);

    private final PasswordResetTokenRepository tokens;
    private final AccountRepository accounts;
    private final SessionRepository sessions;
    private final AuditRepository audit;
    private final PasswordResetMailer mailer;
    private final PasswordEncoder passwordEncoder;
    private final ObjectMapper json;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public PasswordResetService(
            PasswordResetTokenRepository tokens,
            AccountRepository accounts,
            SessionRepository sessions,
            AuditRepository audit,
            PasswordResetMailer mailer,
            PasswordEncoder passwordEncoder,
            ObjectMapper json,
            Clock clock) {
        this.tokens = tokens;
        this.accounts = accounts;
        this.sessions = sessions;
        this.audit = audit;
        this.mailer = mailer;
        this.passwordEncoder = passwordEncoder;
        this.json = json;
        this.clock = clock;
    }

    /**
     * Starts a reset. Mints a token and emails it <b>only</b> when the address
     * belongs to an account that already holds a password hash (ADR-018
     * decision 2) — a Google/Keycloak-only account is left untouched.
     *
     * <p>The caller of this method (the controller) must not let that
     * difference reach the response: every case here — unknown address, a
     * Google-only account, a genuinely emailed reset — is meant to look
     * identical from the outside (ADR-018 decision 3, {@code UC-004} E-3).
     * What this method does <em>not</em> equalise is response time: sending a
     * real email takes measurably longer than the no-op path. That gap is
     * accepted rather than closed here — see {@code SessionService}'s
     * dummy-hash comparison for the shape a real fix would take, and ADR-018's
     * "no rate limiting yet" gap, which this shares.
     *
     * <p>// req: ADR-018
     */
    @Transactional
    public void requestReset(String email) {
        Optional<AccountCredential> found = accounts.findCredentialByEmailIgnoreCase(email);

        if (found.isEmpty() || found.get().passwordHash() == null) {
            audit.append(
                    null,
                    "unauthenticated",
                    AuditAction.PASSWORD_RESET_REQUEST,
                    AuditAction.TARGET_ACCOUNT,
                    null,
                    json.createObjectNode().put("email", email).put("emailed", false));
            return;
        }

        Account account = found.get().account();
        String rawToken = newToken();
        Instant expiresAt = clock.instant().plus(TTL);
        tokens.insert(hash(rawToken), account.id(), expiresAt);
        mailer.sendResetLink(account.email(), rawToken);

        audit.append(
                account.id(),
                account.email(),
                AuditAction.PASSWORD_RESET_REQUEST,
                AuditAction.TARGET_ACCOUNT,
                account.id(),
                json.createObjectNode().put("emailed", true).put("expiresAt", expiresAt.toString()));
    }

    /**
     * Redeems a token: sets the new password, spends the token, and revokes
     * every live session for the account (ADR-018 decision 4).
     *
     * <p>Unlike {@link #requestReset}, a rejected token here is told plainly
     * why — the caller already holds a value that came from their own
     * mailbox, so refusing it discloses nothing about anyone else's account
     * (ADR-018 decision 3).
     *
     * <p>// req: FR-AUTH-002, FR-AUTH-007, ADR-018
     */
    @Transactional
    public void resetPassword(String rawToken, String newRawPassword) {
        byte[] tokenHash = hash(rawToken);
        Optional<PasswordResetToken> found = tokens.find(tokenHash);

        if (found.isEmpty() || !found.get().isUsable(clock.instant())) {
            throw ApiException.badRequest("this reset link is invalid or has expired");
        }

        UUID accountId = found.get().accountId();
        Account account = accounts.findById(accountId).orElseThrow();

        accounts.updatePasswordHash(accountId, passwordEncoder.encode(newRawPassword));
        tokens.markUsed(tokenHash);
        int revoked = sessions.revokeAllFor(accountId);

        audit.append(
                account.id(),
                account.email(),
                AuditAction.PASSWORD_RESET_COMPLETE,
                AuditAction.TARGET_ACCOUNT,
                account.id(),
                json.createObjectNode().put("sessionsRevoked", revoked));
    }

    /** Table hygiene, mirroring {@code SessionService.purgeExpired} — not a security control. */
    @Transactional
    public int purgeExpired() {
        return tokens.deleteExpiredBefore(clock.instant());
    }

    private String newToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** SHA-256 of the token — see {@code SessionService.hash} for why no salt or work factor is needed. */
    private static byte[] hash(String token) {
        return SessionService.hash(token);
    }
}
