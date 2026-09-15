package com.coc.docs.auth;

import com.coc.docs.account.Account;
import com.coc.docs.account.AccountRepository;
import com.coc.docs.account.AccountService;
import com.coc.docs.audit.AuditAction;
import com.coc.docs.audit.AuditRepository;
import com.coc.docs.web.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Mints, emails, and redeems the 6-digit registration code (ADR-021,
 * revision of ADR-014/017). Everything this class does happens <em>before</em>
 * an account exists — {@link #verify} is the one method that finally calls
 * {@code AccountService.register}, unchanged, which is where ADR-017's
 * approval gate still applies exactly as it always has.
 *
 * <p>// req: FR-AUTH-002, ADR-014, ADR-017, ADR-021
 */
@Service
public class RegistrationOtpService {

    private static final int CODE_DIGITS = 6;
    private static final int CODE_SPACE = 1_000_000; // 10^CODE_DIGITS

    /** Fixed, system-wide, no per-deployment variant — see ADR-021 Key Choice 4. */
    public static final Duration TTL = Duration.ofMinutes(10);

    /** Below this, a resend or a resubmission for the same email is refused as premature. */
    public static final Duration RESEND_COOLDOWN = Duration.ofSeconds(60);

    /** Wrong guesses allowed against one still-live code before it must be reissued. */
    public static final int MAX_ATTEMPTS = 5;

    private final RegistrationChallengeRepository challenges;
    private final AccountRepository accountRepository;
    private final AccountService accountService;
    private final AuditRepository audit;
    private final RegistrationOtpMailer mailer;
    private final ObjectMapper json;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public RegistrationOtpService(
            RegistrationChallengeRepository challenges,
            AccountRepository accountRepository,
            AccountService accountService,
            AuditRepository audit,
            RegistrationOtpMailer mailer,
            ObjectMapper json,
            Clock clock) {
        this.challenges = challenges;
        this.accountRepository = accountRepository;
        this.accountService = accountService;
        this.audit = audit;
        this.mailer = mailer;
        this.json = json;
        this.clock = clock;
    }

    /**
     * Step 1: starts (or restarts) a registration.
     *
     * <p>Refused outright if the address already has an account — same 409
     * and message {@code AccountService.register} always gave, just checked
     * earlier so no code is minted or emailed for an address that could
     * never complete registration anyway.
     *
     * <p>A resubmission of the form for an address already mid-verification
     * is treated exactly like {@link #resend}: same cooldown, same
     * incrementing {@code resend_count} — but using whatever display name
     * and password were just typed, in case the caller was correcting a
     * typo from their first attempt.
     */
    @Transactional
    public RegistrationChallenge start(String email, String displayName, String passwordHash) {
        if (accountRepository.findByEmail(email).isPresent()) {
            throw ApiException.conflict("that email address is already registered");
        }

        Optional<RegistrationChallenge> existing = challenges.find(email);
        existing.ifPresent(this::requireCooldownElapsed);
        int resendCount = existing.map(c -> c.resendCount() + 1).orElse(0);

        return issue(email, displayName, passwordHash, resendCount);
    }

    /**
     * Re-sends the code for a registration already in flight — the "I didn't
     * get it" / "it expired" path. Requires no proof of anything beyond the
     * address, same as {@code PasswordResetService.requestReset}; the
     * 60-second cooldown is what stands between this and a mail-bomb.
     */
    @Transactional
    public RegistrationChallenge resend(String email) {
        RegistrationChallenge existing = challenges
                .find(email)
                .orElseThrow(() -> ApiException.badRequest(
                        "No registration is pending for this email. Please start registration again."));

        requireCooldownElapsed(existing);
        return issue(existing.email(), existing.displayName(), existing.passwordHash(), existing.resendCount() + 1);
    }

    /**
     * Step 3: confirms the emailed code and creates the account.
     *
     * <p>Expired and wrong-code are reported distinctly, unlike the
     * anti-enumeration genericism {@code /login-password} uses — see ADR-021
     * Key Choice 5 for why that genericism does not apply here: by this
     * point the caller is the one who supplied this exact email to {@code
     * /register} in their own browser a moment earlier, not a third party
     * probing for someone else's account.
     *
     * <p>Only two things ever mutate the row: a wrong guess increments
     * {@code attempts}, and success deletes it. An expired or
     * attempts-exhausted row is left in place on purpose — a follow-up
     * {@link #resend} still works on it, exactly as it would on a fresh one
     * (ADR-021 Key Choice 3).
     *
     * <p>{@code noRollbackFor}: a wrong guess is refused by throwing {@link
     * ApiException} — Spring's default is to roll back the whole
     * transaction on any unchecked exception, which would silently undo the
     * {@code incrementAttempts} write this method just made and erase the
     * record of the guess ever happening. Without this, the 5-attempt cap
     * never actually caps anything: {@code attempts} would forever read 0,
     * and every wrong guess would report "incorrect" instead of eventually
     * "too many attempts" (caught by {@code
     * RegistrationOtpServiceIntegrationTest.rejectsAWrongCode} and {@code
     * .locksOutAfterMaxAttempts} against a real database — no Docker in
     * this environment meant this bug shipped once already before CI
     * caught it). Same precedent as {@code
     * SessionService.signInWithPassword}'s identical
     * {@code noRollbackFor(GoogleIdTokenVerifier.AuthenticationFailure.class)},
     * for the same reason.
     */
    @Transactional(noRollbackFor = ApiException.class)
    public Account verify(String email, String rawCode) {
        RegistrationChallenge found = challenges
                .find(email)
                .orElseThrow(() -> ApiException.badRequest(
                        "No verification code is pending for this email. Please start registration again."));

        if (!found.isUsable(clock.instant())) {
            throw ApiException.badRequest("This verification code has expired. Request a new one.");
        }
        if (found.attempts() >= MAX_ATTEMPTS) {
            throw ApiException.badRequest("Too many incorrect attempts. Request a new code.");
        }
        if (!MessageDigest.isEqual(hash(rawCode), found.codeHash())) {
            challenges.incrementAttempts(found.id());
            throw ApiException.badRequest("That code is incorrect. Please try again.");
        }

        challenges.delete(email);
        return accountService.register(found.email(), found.displayName(), found.passwordHash());
    }

    /** Table hygiene, mirroring {@code PasswordResetService.purgeExpired} — not a security control. */
    @Transactional
    public int purgeExpired() {
        return challenges.deleteExpiredBefore(clock.instant());
    }

    private RegistrationChallenge issue(String email, String displayName, String passwordHash, int resendCount) {
        String rawCode = newCode();
        Instant expiresAt = clock.instant().plus(TTL);

        RegistrationChallenge created =
                challenges.replace(email, displayName, passwordHash, hash(rawCode), expiresAt, resendCount);
        mailer.sendCode(created.email(), rawCode, TTL);

        audit.append(
                null,
                email,
                AuditAction.ACCOUNT_REGISTER_OTP_REQUEST,
                AuditAction.TARGET_ACCOUNT,
                null,
                json.createObjectNode()
                        .put("email", email)
                        .put("expiresAt", expiresAt.toString())
                        .put("resendCount", resendCount));

        return created;
    }

    private void requireCooldownElapsed(RegistrationChallenge challenge) {
        Instant retryAfter = challenge.issuedAt().plus(RESEND_COOLDOWN);
        if (clock.instant().isBefore(retryAfter)) {
            throw new ApiException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "A code was just sent to this address. Please wait a moment before requesting another.");
        }
    }

    /** Zero-padded so every code is exactly 6 digits, never fewer. */
    private String newCode() {
        return String.format(Locale.ROOT, "%0" + CODE_DIGITS + "d", random.nextInt(CODE_SPACE));
    }

    /** SHA-256 of the code — see {@code SessionService.hash} for why no salt or work factor is needed. */
    private static byte[] hash(String code) {
        return SessionService.hash(code);
    }
}
