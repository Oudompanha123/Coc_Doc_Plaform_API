package com.coc.docs.claudeusage;

import com.coc.docs.account.Account;
import com.coc.docs.account.AccountRepository;
import com.coc.docs.audit.AuditAction;
import com.coc.docs.audit.AuditRepository;
import com.coc.docs.auth.CurrentUser;
import com.coc.docs.web.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The claude-team-usage CLI's browser login (ADR-060): OAuth 2.0 for native
 * apps (RFC 8252) — an authorization code delivered to a 127.0.0.1 redirect,
 * bound to a PKCE S256 challenge (RFC 7636).
 *
 * <ol>
 *   <li>{@link #approve} — a signed-in member clicks Authorize on
 *       {@code /cli/authorize}. Stores the SHA-256 of a fresh one-time code,
 *       bound to their account and the CLI's code challenge, valid for
 *       {@link #CODE_TTL}. No token exists yet.</li>
 *   <li>The browser carries the raw code to the CLI's loopback listener.</li>
 *   <li>{@link #exchange} — the CLI presents the code and its verifier. The
 *       code is consumed first (atomically, so single-use even when the
 *       verifier is wrong), then the verifier is checked, then the account is
 *       re-checked live, then an ordinary {@code claude_usage_ingest_token} is
 *       minted through {@link ClaudeUsageIngestTokenService#createFor}.</li>
 * </ol>
 *
 * <p>Every exchange failure is the same {@code 400 invalid_grant}: an attacker
 * probing codes learns nothing about which check failed.
 *
 * <p>// req: FR-USAGE-002, NFR-SEC-CHANNEL, ADR-047, ADR-060
 */
@Service
public class ClaudeUsageCliAuthService {

    static final Duration CODE_TTL = Duration.ofMinutes(2);
    private static final int CODE_BYTES = 32;

    /** base64url of a 32-byte SHA-256 digest, unpadded. */
    private static final Pattern CHALLENGE = Pattern.compile("^[A-Za-z0-9_-]{43}$");
    /** RFC 7636 §4.1 code_verifier: 43–128 unreserved characters. */
    private static final Pattern VERIFIER = Pattern.compile("^[A-Za-z0-9._~-]{43,128}$");
    private static final Pattern CODE = Pattern.compile("^[A-Za-z0-9_-]{43}$");

    private final ClaudeUsageCliAuthorizationRepository codes;
    private final ClaudeUsageIngestTokenService tokens;
    private final AccountRepository accounts;
    private final AuditRepository audit;
    private final ObjectMapper json;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    @Autowired
    public ClaudeUsageCliAuthService(
            ClaudeUsageCliAuthorizationRepository codes,
            ClaudeUsageIngestTokenService tokens,
            AccountRepository accounts,
            AuditRepository audit,
            ObjectMapper json) {
        this(codes, tokens, accounts, audit, json, Clock.systemUTC());
    }

    ClaudeUsageCliAuthService(
            ClaudeUsageCliAuthorizationRepository codes,
            ClaudeUsageIngestTokenService tokens,
            AccountRepository accounts,
            AuditRepository audit,
            ObjectMapper json,
            Clock clock) {
        this.codes = codes;
        this.tokens = tokens;
        this.accounts = accounts;
        this.audit = audit;
        this.json = json;
        this.clock = clock;
    }

    /** What {@link #exchange} hands back: the minted token and whose it is. */
    public record Exchanged(ClaudeUsageIngestTokenService.MintedToken minted, Account account) {}

    /** Returns the raw one-time code. It is never stored or logged; only its hash is. */
    @Transactional
    public String approve(CurrentUser caller, String codeChallenge, String device) {
        if (codeChallenge == null || !CHALLENGE.matcher(codeChallenge).matches()) {
            throw ApiException.badRequest("codeChallenge must be a base64url SHA-256 digest (43 characters)");
        }
        String label = cleanDevice(device);

        Instant now = clock.instant();
        codes.deleteExpiredBefore(now.minus(Duration.ofDays(1)));

        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes());
        UUID id = codes.insert(caller.id(), sha256Hex(raw), codeChallenge, label, now.plus(CODE_TTL));

        audit.append(
                caller.id(),
                caller.email(),
                AuditAction.CLAUDE_USAGE_CLI_AUTHORIZE,
                AuditAction.TARGET_CLAUDE_USAGE_CLI_AUTHORIZATION,
                id,
                json.createObjectNode().put("device", label));
        return raw;
    }

    /**
     * {@code noRollbackFor}: a failed exchange must still leave the code
     * consumed — otherwise a wrong verifier would roll {@code used_at} back
     * and the same code could be tried again.
     */
    @Transactional(noRollbackFor = ApiException.class)
    public Exchanged exchange(String code, String codeVerifier) {
        if (code == null || !CODE.matcher(code).matches()
                || codeVerifier == null || !VERIFIER.matcher(codeVerifier).matches()) {
            throw invalidGrant();
        }

        ClaudeUsageCliAuthorizationRepository.ConsumedCode consumed =
                codes.consume(sha256Hex(code)).orElseThrow(ClaudeUsageCliAuthService::invalidGrant);

        String expected = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(sha256(codeVerifier.getBytes(StandardCharsets.US_ASCII)));
        if (!MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.US_ASCII),
                consumed.codeChallenge().getBytes(StandardCharsets.US_ASCII))) {
            throw invalidGrant();
        }

        // Re-checked now, not trusted from approval time: an account
        // deactivated or downgraded in the two minutes between gets nothing.
        Optional<Account> account = accounts.findById(consumed.accountId());
        // Checked here rather than left to createFor's own guard, so createFor
        // never throws inside this transaction and marks it rollback-only.
        if (account.isEmpty() || !ClaudeUsageIngestTokenService.mayHoldToken(account.get())) {
            throw invalidGrant();
        }
        return new Exchanged(tokens.createFor(account.get(), "CLI — " + consumed.device()), account.get());
    }

    /**
     * The machine name the CLI reported, shown on the consent screen and as
     * the token's label. Untrusted text: control characters dropped, length
     * capped to the V51/V35 column limits.
     */
    static String cleanDevice(String device) {
        String cleaned = device == null ? "" : device.replaceAll("\\p{Cntrl}", "").trim();
        if (cleaned.isEmpty()) {
            cleaned = "unnamed machine";
        }
        return cleaned.length() > 60 ? cleaned.substring(0, 60) : cleaned;
    }

    private static ApiException invalidGrant() {
        return ApiException.badRequest("invalid_grant: the sign-in code is invalid, expired, or already used — run the CLI again");
    }

    private byte[] randomBytes() {
        byte[] bytes = new byte[CODE_BYTES];
        random.nextBytes(bytes);
        return bytes;
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required and absent from this JVM", e);
        }
    }

    private static String sha256Hex(String raw) {
        return HexFormat.of().formatHex(sha256(raw.getBytes(StandardCharsets.UTF_8)));
    }
}
