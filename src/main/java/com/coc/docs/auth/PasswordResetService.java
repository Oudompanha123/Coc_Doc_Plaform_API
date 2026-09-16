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
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Mints and redeems password-reset tokens (ADR-018, revision of ADR-014's
 * "no password reset flow exists" gap).
 *
 * <p>{@link #requestReset} no longer holds ADR-018's full anti-enumeration
 * property — ADR-019 carved out "does an account exist at all" as a case
 * the caller is now deliberately told, on explicit product instruction.
 *
 * <p>// req: FR-AUTH-002, ADR-014, ADR-018, ADR-019
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
     * Starts a reset — or, for a Google-only account, a first password
     * (ADR-029, revision of ADR-018 decision 2). Mints a token and emails a
     * link for <b>every account that exists</b>, regardless of whether it
     * already holds a password hash; {@code mailer.sendResetLink}'s {@code
     * hadExistingPassword} flag only changes the emailed wording ("reset"
     * vs. "set"), not whether anything is sent.
     *
     * <p><b>Revised by ADR-019:</b> an address with no account at all is not
     * folded into the same generic response — it throws, and the exact
     * message reaches the caller as a {@code 404}. Every account that does
     * exist — password-holding or Google-only alike — now gets the
     * identical treatment: a token, an email, and the same generic {@code
     * 200} (ADR-018 decision 3, and — since ADR-029 — one fewer side channel
     * for telling the two apart than before: previously the API response
     * was already identical, but whether an email actually arrived was not).
     * {@code /login-password} is unaffected by any of this — see ADR-019.
     *
     * <p>What this method does <em>not</em> equalise, on either side of the
     * ADR-019 split, is response time: sending a real email takes measurably
     * longer than a 404's no-op path. That gap is accepted rather than closed
     * here — see {@code SessionService}'s dummy-hash comparison for the shape
     * a real fix would take, and ADR-018's "no rate limiting yet" gap, which
     * this shares and — per ADR-019 — now matters more than it did.
     *
     * <p>// req: ADR-018, ADR-019, ADR-029
     */
    @Transactional
    public void requestReset(String email) {
        Optional<AccountCredential> found = accounts.findCredentialByEmailIgnoreCase(email);

        if (found.isEmpty()) {
            audit.append(
                    null,
                    "unauthenticated",
                    AuditAction.PASSWORD_RESET_REQUEST,
                    AuditAction.TARGET_ACCOUNT,
                    null,
                    json.createObjectNode().put("email", email).put("emailed", false).put("reason", "no_account"));
            // Exact wording is part of ADR-018's decision, not an
            // implementation detail — the frontend keys off this same string.
            throw new ApiException(HttpStatus.NOT_FOUND, "No account found with this email address.");
        }

        Account account = found.get().account();
        boolean hadExistingPassword = found.get().passwordHash() != null;

        String rawToken = newToken();
        Instant expiresAt = clock.instant().plus(TTL);
        tokens.insert(hash(rawToken), account.id(), expiresAt);
        mailer.sendResetLink(account.email(), rawToken, hadExistingPassword);

        audit.append(
                account.id(),
                account.email(),
                AuditAction.PASSWORD_RESET_REQUEST,
                AuditAction.TARGET_ACCOUNT,
                account.id(),
                json.createObjectNode()
                        .put("emailed", true)
                        .put("expiresAt", expiresAt.toString())
                        .put("hadPasswordAlready", hadExistingPassword));
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
     * <p><b>Distinguishes an initial set from a genuine reset for its return
     * message and audit {@code detail.kind} only (ADR-029)</b> — the same
     * "read the old state before it changes" shape {@code
     * SessionService.changeOwnPassword} already uses (ADR-030). The write
     * itself ({@code AccountRepository.updatePasswordHash}) was already
     * unconditional and needed no change: an account with no existing hash
     * was never special-cased there.
     *
     * <p>// req: FR-AUTH-002, FR-AUTH-007, ADR-018, ADR-029
     */
    @Transactional
    public String resetPassword(String rawToken, String newRawPassword) {
        byte[] tokenHash = hash(rawToken);
        Optional<PasswordResetToken> found = tokens.find(tokenHash);

        if (found.isEmpty() || !found.get().isUsable(clock.instant())) {
            throw ApiException.badRequest("this reset link is invalid or has expired");
        }

        UUID accountId = found.get().accountId();
        AccountCredential credential = accounts.findCredentialById(accountId).orElseThrow();
        Account account = credential.account();
        boolean settingInitialPassword = credential.passwordHash() == null;

        accounts.updatePasswordHash(accountId, passwordEncoder.encode(newRawPassword));
        tokens.markUsed(tokenHash);
        int revoked = sessions.revokeAllFor(accountId);

        audit.append(
                account.id(),
                account.email(),
                AuditAction.PASSWORD_RESET_COMPLETE,
                AuditAction.TARGET_ACCOUNT,
                account.id(),
                json.createObjectNode()
                        .put("sessionsRevoked", revoked)
                        .put("kind", settingInitialPassword ? "initial-set" : "reset"));

        return settingInitialPassword
                ? "Your password has been set. You can now log in with your email and password."
                : "Your password has been reset. You can now log in.";
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
