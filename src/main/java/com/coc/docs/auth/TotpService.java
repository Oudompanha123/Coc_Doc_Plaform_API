package com.coc.docs.auth;

import com.coc.docs.account.Account;
import com.coc.docs.account.AccountCredential;
import com.coc.docs.account.AccountRepository;
import com.coc.docs.audit.AuditAction;
import com.coc.docs.audit.AuditRepository;
import com.coc.docs.config.OtpProperties;
import com.coc.docs.web.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.samstevens.totp.code.CodeGenerator;
import dev.samstevens.totp.code.DefaultCodeGenerator;
import dev.samstevens.totp.code.HashingAlgorithm;
import dev.samstevens.totp.exceptions.CodeGenerationException;
import dev.samstevens.totp.exceptions.QrGenerationException;
import dev.samstevens.totp.qr.QrData;
import dev.samstevens.totp.qr.QrGenerator;
import dev.samstevens.totp.qr.ZxingPngQrGenerator;
import dev.samstevens.totp.secret.DefaultSecretGenerator;
import dev.samstevens.totp.secret.SecretGenerator;
import dev.samstevens.totp.time.SystemTimeProvider;
import dev.samstevens.totp.time.TimeProvider;
import dev.samstevens.totp.util.Utils;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Enrolls, confirms, disables, and verifies TOTP second-factor codes
 * (ADR-031). Mirrors {@link RegistrationOtpService}'s structure: a challenge
 * of sorts (here, the pending {@code account_totp} row and the separate
 * {@code mfa_login_challenge} row) that a code either confirms or doesn't.
 *
 * <p><b>Deliberately does not issue sessions.</b> {@link
 * #verifyLoginChallenge} returns the {@link Account} once its code is
 * confirmed; the caller ({@code AuthController}) hands that to {@code
 * SessionService.issueForAccount} to actually mint one. Session issuance
 * stays {@code SessionService}'s sole responsibility, and this keeps the two
 * classes from depending on each other in a cycle — {@code SessionService}
 * mints the pending challenge this class redeems (in {@code
 * signInWithPassword}), and this class never calls back into it.
 *
 * <p>// req: FR-AUTH-009, ADR-031
 */
@Service
public class TotpService {

    /** The pending-MFA cookie {@code AuthController} sets after a correct password and reads back at {@code /mfa/verify}. */
    public static final String MFA_PENDING_COOKIE_NAME = "coc_mfa_pending";

    private static final int DIGITS = 6;
    private static final int PERIOD_SECONDS = 30;

    /** +/-1 step, matching Google Authenticator's own tolerance for clock drift. */
    private static final int ALLOWED_STEP_DRIFT = 1;

    private static final String ISSUER = "Coc Docs Platform";

    private final AccountTotpRepository totps;
    private final MfaLoginChallengeRepository challenges;
    private final AccountRepository accounts;
    private final AuditRepository audit;
    private final OtpProperties otpProperties;
    private final PasswordEncoder passwordEncoder;
    private final ObjectMapper json;
    private final Clock clock;

    private final SecretGenerator secretGenerator = new DefaultSecretGenerator();
    private final CodeGenerator codeGenerator = new DefaultCodeGenerator();
    private final QrGenerator qrGenerator = new ZxingPngQrGenerator();
    private final TimeProvider timeProvider = new SystemTimeProvider();

    public TotpService(
            AccountTotpRepository totps,
            MfaLoginChallengeRepository challenges,
            AccountRepository accounts,
            AuditRepository audit,
            OtpProperties otpProperties,
            PasswordEncoder passwordEncoder,
            ObjectMapper json,
            Clock clock) {
        this.totps = totps;
        this.challenges = challenges;
        this.accounts = accounts;
        this.audit = audit;
        this.otpProperties = otpProperties;
        this.passwordEncoder = passwordEncoder;
        this.json = json;
        this.clock = clock;
    }

    /** The material a setup wizard needs: the raw secret and a scannable QR code. */
    public record SetupMaterial(String secret, String qrCodeDataUri, String issuer, String accountLabel) {}

    // -----------------------------------------------------------------------
    // Setup (Settings -> Security)
    // -----------------------------------------------------------------------

    /**
     * Starts (or restarts) enrollment. Refuses if this account already has a
     * confirmed enrollment — disable it first, rather than silently
     * replacing a live secret out from under it.
     *
     * <p><b>Also refuses an account with no password (revision of
     * ADR-031).</b> TOTP only ever gates {@code /login-password} — there is
     * no password sign-in on a Google-only account for a second factor to
     * protect yet. Enforced here, not only hidden in {@code SecurityTab}'s
     * toggle: a client-side-only gate would still let a direct API call
     * enroll TOTP on an account that could never actually use it to sign in,
     * which is confusing state to be in, not a security hole to close —
     * this is a product-clarity rule, matching how the rest of this backend
     * never treats a UI omission as the actual control (CONST-TECH-07).
     */
    @Transactional
    public SetupMaterial startSetup(CurrentUser caller) {
        if (!caller.account().hasPassword()) {
            throw ApiException.badRequest(
                    "Set a password before turning on two-factor authentication.");
        }

        boolean alreadyConfirmed = totps.find(caller.id()).filter(AccountTotp::isConfirmed).isPresent();
        if (alreadyConfirmed) {
            throw ApiException.badRequest(
                    "Two-factor authentication is already enabled. Disable it before setting up a new device.");
        }

        String secret = secretGenerator.generate();
        totps.upsertPending(caller.id(), secret);
        return new SetupMaterial(secret, buildQrCodeDataUri(caller.email(), secret), ISSUER, caller.email());
    }

    /**
     * Confirms a pending enrollment with a code from the app the QR/secret
     * was just added to. A wrong code is reported plainly — same "the
     * caller just looked at this value themselves" reasoning ADR-021 already
     * applies to a mistyped registration code; there is no third party here
     * for a generic message to protect.
     */
    @Transactional(noRollbackFor = ApiException.class)
    public void confirmSetup(CurrentUser caller, String code) {
        AccountTotp totp = totps.find(caller.id())
                .orElseThrow(() -> ApiException.badRequest(
                        "No two-factor setup is in progress. Start setup again."));
        if (totp.isConfirmed()) {
            throw ApiException.badRequest("Two-factor authentication is already enabled.");
        }

        verify(totp, code);
        totps.confirm(caller.id());
        audit.append(
                caller.id(), caller.email(), AuditAction.MFA_ENABLED,
                AuditAction.TARGET_ACCOUNT, caller.id(), json.createObjectNode());
    }

    /**
     * Turns TOTP off. Requires and verifies the current password first — the
     * same reasoning {@code SessionService.changeOwnPassword} already
     * applies to any security-lowering self-service action (ADR-022): a
     * session alone must not be enough to silently remove the account's own
     * second factor.
     */
    @Transactional
    public void disable(CurrentUser caller, String currentPassword) {
        AccountCredential credential = accounts.findCredentialById(caller.id()).orElseThrow();
        if (credential.passwordHash() == null
                || currentPassword == null
                || currentPassword.isBlank()
                || !passwordEncoder.matches(currentPassword, credential.passwordHash())) {
            throw ApiException.badRequest("your current password is incorrect");
        }

        if (totps.delete(caller.id()) == 0) {
            throw ApiException.badRequest("Two-factor authentication is not enabled.");
        }
        audit.append(
                caller.id(), caller.email(), AuditAction.MFA_DISABLED,
                AuditAction.TARGET_ACCOUNT, caller.id(), json.createObjectNode());
    }

    /** What {@code GET /api/v1/auth/mfa/status} reports — nothing more than whether it's on. */
    @Transactional(readOnly = true)
    public boolean isEnabledFor(UUID accountId) {
        return totps.find(accountId).filter(AccountTotp::isConfirmed).isPresent();
    }

    // -----------------------------------------------------------------------
    // Login-time redemption (the challenge SessionService.signInWithPassword mints)
    // -----------------------------------------------------------------------

    /**
     * Redeems the pending-MFA cookie a successful password check left
     * behind. Resolves the challenge, verifies the code against the
     * account's confirmed secret, and returns the {@link Account} — the
     * caller ({@code AuthController}) still has to call {@code
     * SessionService.issueForAccount} to actually sign them in.
     *
     * <p>A wrong or reused code leaves the challenge row in place (only the
     * transaction rolls back the code-verification's own side effects,
     * which {@code noRollbackFor} prevents) — a retry within the same
     * 5-minute window reuses the same challenge rather than forcing a fresh
     * password submission. An expired one is deleted outright: it can't be
     * retried either way.
     *
     * <p>// req: FR-AUTH-009, ADR-031
     */
    @Transactional(noRollbackFor = ApiException.class)
    public Account verifyLoginChallenge(String rawChallengeToken, String code) {
        byte[] tokenHash = SessionService.hash(rawChallengeToken);
        MfaLoginChallenge challenge = challenges
                .find(tokenHash)
                .orElseThrow(() -> ApiException.badRequest(
                        "This sign-in attempt has expired. Please sign in again."));

        if (!challenge.isUsable(clock.instant())) {
            challenges.delete(tokenHash);
            throw ApiException.badRequest("This sign-in attempt has expired. Please sign in again.");
        }

        AccountTotp totp = totps
                .find(challenge.accountId())
                .filter(AccountTotp::isConfirmed)
                .orElseThrow(() -> {
                    // Two-factor was disabled between the password check and
                    // this call — the challenge no longer means anything.
                    challenges.delete(tokenHash);
                    return ApiException.badRequest("This sign-in attempt has expired. Please sign in again.");
                });

        verify(totp, code);

        Account account = accounts
                .findById(challenge.accountId())
                .filter(Account::canSignIn)
                .orElseThrow(() -> ApiException.forbidden("this account cannot sign in"));

        challenges.delete(tokenHash);
        return account;
    }

    // -----------------------------------------------------------------------
    // Code verification, shared by setup confirmation and login redemption
    // -----------------------------------------------------------------------

    /**
     * Verifies {@code code} against {@code totp}, throwing a plain {@link
     * ApiException} on any refusal.
     *
     * <p>When {@code app.otp.enabled} is false (developer/test bypass —
     * {@link OtpDevModeGuard} guarantees this can't be true outside a
     * {@code dev}/{@code test} profile), only the fixed {@code
     * app.otp.dev-code} is accepted; the real secret is never consulted, so
     * a wrong QR scan or a broken authenticator app doesn't block testing
     * the rest of the flow.
     *
     * <p>Otherwise, a real code is checked against every step in a +/-1
     * window (matching the app's own tolerance) and, on a match, {@code
     * last_used_step} both records the acceptance and refuses that same step
     * a second time (ADR-031 Key Choice 3) — without this, a captured code
     * could be replayed for the rest of its ~90-second validity window.
     */
    private void verify(AccountTotp totp, String code) {
        if (!otpProperties.enabled()) {
            if (constantTimeEquals(otpProperties.devCode(), code)) {
                return;
            }
            throw ApiException.badRequest("That code is incorrect.");
        }

        long currentStep = Math.floorDiv(timeProvider.getTime(), PERIOD_SECONDS);
        Long matchedStep = null;
        for (long candidate = currentStep - ALLOWED_STEP_DRIFT;
                candidate <= currentStep + ALLOWED_STEP_DRIFT;
                candidate++) {
            if (constantTimeEquals(generateCode(totp.secret(), candidate), code)) {
                matchedStep = candidate;
                break;
            }
        }

        if (matchedStep == null) {
            throw ApiException.badRequest(
                    "That code is incorrect. Please check the time on your device and try again.");
        }
        if (totp.lastUsedStep() != null && matchedStep <= totp.lastUsedStep()) {
            throw ApiException.badRequest("That code has already been used. Wait for the next code and try again.");
        }
        totps.updateLastUsedStep(totp.accountId(), matchedStep);
    }

    private String generateCode(String secret, long counter) {
        try {
            return codeGenerator.generate(secret, counter);
        } catch (CodeGenerationException e) {
            throw new IllegalStateException("TOTP code generation failed", e);
        }
    }

    /** Same reasoning as {@code SessionService.hash}'s callers: a fixed-length code costs nothing extra to compare this way. */
    private static boolean constantTimeEquals(String expected, String actual) {
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
    }

    private String buildQrCodeDataUri(String accountLabel, String secret) {
        QrData data = new QrData.Builder()
                .label(accountLabel)
                .secret(secret)
                .issuer(ISSUER)
                .algorithm(HashingAlgorithm.SHA1)
                .digits(DIGITS)
                .period(PERIOD_SECONDS)
                .build();
        try {
            byte[] png = qrGenerator.generate(data);
            return Utils.getDataUriForImage(png, qrGenerator.getImageMimeType());
        } catch (QrGenerationException e) {
            throw new IllegalStateException("QR code generation failed", e);
        }
    }
}
