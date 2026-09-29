package com.coc.docs.claudeusage;

import com.coc.docs.account.Account;
import com.coc.docs.account.AccountRepository;
import com.coc.docs.account.AppRole;
import com.coc.docs.audit.AuditAction;
import com.coc.docs.audit.AuditRepository;
import com.coc.docs.auth.CurrentUser;
import com.coc.docs.web.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Mints, lists, revokes, and resolves {@code claude_usage_ingest_token} rows
 * (V35) — the per-account alternative to {@code ClaudeUsageProperties}'
 * single shared secret that {@link com.coc.docs.web.ClaudeUsageIngestController}
 * now accepts as well.
 *
 * <p>Each token is scoped to the account that created it: {@link #resolve}
 * returns that account, and {@code ClaudeUsageIngestController} requires the
 * ingest request's {@code email} to match it. This is a deliberate departure
 * from the shared secret's own design (ADR-035 §2: "the secret's only job is
 * proving the caller may write at all, not who they are") — a personal token
 * is exactly the finer-grained accountability ADR-035 traded away when it
 * rejected per-member tokens, so revoking one person's token, or downgrading
 * their role, no longer affects anyone else's ability to report.
 *
 * <p>// req: FR-USAGE-002, NFR-SEC-CHANNEL, ADR-047, ADR-053
 */
@Service
public class ClaudeUsageIngestTokenService {

    /** Matches {@code PasswordResetService}/{@code SessionService}: 256 bits, opaque, unguessable. */
    private static final int TOKEN_BYTES = 32;

    /** Makes a leaked value recognisable in logs, screenshots, and secret scanners. */
    private static final String TOKEN_PREFIX = "coc_";

    /**
     * Only an account at this rank or above may mint or use an ingest token.
     * Widened from {@code ADMIN} to {@code USER} on direct developer
     * instruction (ADR-053, revising this ADR's own original scoping) — V35's
     * migration comment still says "each admin / super_admin generates their
     * own token", which was already stale (it cites a nonexistent "ADR-042";
     * see ADR-047) and is left unedited per this repo's rule against
     * modifying an applied migration's SQL. Re-checked on every
     * {@link #resolve}, not just at creation, so a role downgrade takes
     * effect immediately without anyone having to revoke the token
     * explicitly.
     */
    private static final AppRole MIN_ROLE = AppRole.USER;

    private final ClaudeUsageIngestTokenRepository tokens;
    private final AccountRepository accounts;
    private final AuditRepository audit;
    private final ObjectMapper json;
    private final SecureRandom random = new SecureRandom();

    public ClaudeUsageIngestTokenService(
            ClaudeUsageIngestTokenRepository tokens,
            AccountRepository accounts,
            AuditRepository audit,
            ObjectMapper json) {
        this.tokens = tokens;
        this.accounts = accounts;
        this.audit = audit;
        this.json = json;
    }

    /** The raw bearer value, shown to its owner exactly once. Never reconstructible from {@link ClaudeUsageIngestToken#tokenHash()}. */
    public record MintedToken(ClaudeUsageIngestToken token, String rawValue) {}

    /**
     * Mints a new token for the caller's own account. The caller's rank is not
     * re-checked here beyond what {@code SecurityConfig} already enforces on
     * the route ({@code hasRole(ADMIN)}) — {@link #resolve} is what re-checks
     * rank on every use, which is the check that actually matters once a
     * token exists.
     */
    @Transactional
    public MintedToken create(CurrentUser caller, String label) {
        return mint(caller.id(), caller.email(), label);
    }

    /**
     * Same token, same audit entry, for an account proven by something other
     * than a session — the CLI browser login's code + PKCE exchange (ADR-060),
     * which has already checked {@link #MIN_ROLE} and {@link Account#canSignIn()}
     * itself. Deliberately not a second minting path: this is {@link #create}.
     */
    @Transactional
    public MintedToken createFor(Account account, String label) {
        if (!mayHoldToken(account)) {
            throw ApiException.forbidden("this account may not hold an ingest token");
        }
        return mint(account.id(), account.email(), label);
    }

    /** The same live check {@link #resolve} applies on every ingest: can sign in, and at least {@link #MIN_ROLE}. */
    public static boolean mayHoldToken(Account account) {
        return account.canSignIn() && account.role().isAtLeast(MIN_ROLE);
    }

    private MintedToken mint(UUID accountId, String email, String label) {
        String trimmedLabel = label == null ? "" : label.trim();
        if (trimmedLabel.isEmpty()) {
            throw ApiException.badRequest("label is required");
        }

        String raw = TOKEN_PREFIX + HexFormat.of().formatHex(randomBytes());
        ClaudeUsageIngestToken saved = tokens.insert(accountId, hash(raw), trimmedLabel);

        audit.append(
                accountId,
                email,
                AuditAction.CLAUDE_USAGE_INGEST_TOKEN_CREATE,
                AuditAction.TARGET_CLAUDE_USAGE_INGEST_TOKEN,
                saved.id(),
                json.createObjectNode().put("label", trimmedLabel));

        return new MintedToken(saved, raw);
    }

    /** Every live token the caller holds, newest first — never the hash, never the raw value. */
    public List<ClaudeUsageIngestToken> list(CurrentUser caller) {
        return tokens.listActiveByAccount(caller.id());
    }

    /** Refuses to revoke a token belonging to a different account, same as {@code SessionService.revokeSession}'s own-session-only rule. */
    @Transactional
    public void revoke(CurrentUser caller, UUID tokenId) {
        int updated = tokens.revoke(tokenId, caller.id());
        if (updated == 0) {
            throw ApiException.notFound("usage token " + tokenId);
        }

        audit.append(
                caller.id(),
                caller.email(),
                AuditAction.CLAUDE_USAGE_INGEST_TOKEN_REVOKE,
                AuditAction.TARGET_CLAUDE_USAGE_INGEST_TOKEN,
                tokenId,
                json.createObjectNode());
    }

    /**
     * Resolves a raw bearer value presented to {@code POST /api/ingest} into
     * the account it belongs to, or empty if the value is not a live,
     * authorized token.
     *
     * <p>Three checks, not one: the token row must exist and be unrevoked, its
     * account must still be able to sign in ({@link Account#canSignIn()}),
     * and that account must still hold at least {@link #MIN_ROLE} — exactly
     * V35's own documented guarantee that a role downgrade or deactivation
     * takes effect immediately, without touching this table. A match updates
     * {@code last_used_at} as a side effect.
     */
    @Transactional
    public Optional<Account> resolve(String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return Optional.empty();
        }

        Optional<ClaudeUsageIngestToken> found = tokens.findActiveByHash(hash(rawValue));
        if (found.isEmpty()) {
            return Optional.empty();
        }
        ClaudeUsageIngestToken tokenRow = found.get();

        Optional<Account> account = accounts.findById(tokenRow.accountId());
        if (account.isEmpty() || !mayHoldToken(account.get())) {
            return Optional.empty();
        }

        tokens.touchLastUsed(tokenRow.id());
        return account;
    }

    private byte[] randomBytes() {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        return bytes;
    }

    /** SHA-256 hex digest of the raw bearer value (including its {@code coc_} prefix) — matches V35's column comment exactly. */
    private static String hash(String rawValue) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(rawValue.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required and absent from this JVM", e);
        }
    }
}
