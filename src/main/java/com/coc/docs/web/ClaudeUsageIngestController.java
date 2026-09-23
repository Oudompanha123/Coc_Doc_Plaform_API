package com.coc.docs.web;

import com.coc.docs.account.Account;
import com.coc.docs.claudeusage.ClaudeUsageIngestTokenService;
import com.coc.docs.claudeusage.ClaudeUsageModelTokens;
import com.coc.docs.claudeusage.ClaudeUsageProperties;
import com.coc.docs.claudeusage.ClaudeUsageService;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Where a Claude Code usage reporter script pushes one day's running total.
 *
 * <p>{@code /api/ingest}, deliberately outside {@code /api/v1/**}: a reporter
 * script's configuration should not need to change when the admin API is
 * version-bumped, the same reasoning {@code NFR-SEC-CHANNEL} and the threat
 * model (TM-028) already wrote this route's name against before this
 * controller existed.
 *
 * <p><b>Authenticated by a bearer credential, not a session.</b> This is the
 * one deliberate exception to this backend's whole authorization model
 * (ADR-011: every other route is enforced from a session the caller cannot
 * forge). {@code SecurityConfig} {@code permitAll()}s this path for exactly
 * that reason — the check below, not the filter chain, is what protects it —
 * and per {@code NFR-SEC-CHANNEL}'s own wording this is explicitly not a
 * template for an editorial write path.
 *
 * <p><b>Two credential shapes are accepted (ADR-044, revising ADR-035 §2):</b>
 * {@code ClaudeUsageProperties}' single shared secret — unscoped, may report
 * as any email, kept only for deployments already configured with it — and a
 * per-account token minted from {@code POST /api/v1/me/usage-tokens}, which
 * {@link ClaudeUsageIngestTokenService#resolve} restricts to its own
 * account's email. {@code INGEST_URL} is required either way; {@code
 * INGEST_SECRET} is not — see {@code push-usage.mjs}'s own {@code
 * canReport} gate, unaffected by which credential shape fills it.
 *
 * <p>// req: FR-USAGE-002, NFR-SEC-CHANNEL, ADR-035, ADR-036, ADR-044
 */
@RestController
public class ClaudeUsageIngestController {

    private static final String BEARER_PREFIX = "Bearer ";

    private final ClaudeUsageService usage;
    private final ClaudeUsageProperties properties;
    private final ClaudeUsageIngestTokenService tokens;

    public ClaudeUsageIngestController(
            ClaudeUsageService usage, ClaudeUsageProperties properties, ClaudeUsageIngestTokenService tokens) {
        this.usage = usage;
        this.properties = properties;
        this.tokens = tokens;
    }

    @PostMapping("/api/ingest")
    ApiDtos.ClaudeUsageIngestResponse ingest(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @Valid @RequestBody ApiDtos.ClaudeUsageIngestRequest request) {

        authorize(authorization, request.email());

        ClaudeUsageService.IngestOutcome outcome = usage.recordUsage(
                request.email(),
                request.displayName(),
                request.date(),
                request.input(),
                request.output(),
                request.cacheRead(),
                request.cacheCreation(),
                request.models(),
                toDomain(request.byModel()));

        return new ApiDtos.ClaudeUsageIngestResponse(outcome.action());
    }

    /** The wire DTO stays out of the service layer (ApiDtos.java's own stated rule) — this is the one conversion point. */
    private static Map<String, ClaudeUsageModelTokens> toDomain(Map<String, ApiDtos.ClaudeUsageModelTokensRequest> byModel) {
        if (byModel == null) {
            return Map.of();
        }
        Map<String, ClaudeUsageModelTokens> out = new LinkedHashMap<>();
        byModel.forEach((model, t) ->
                out.put(model, new ClaudeUsageModelTokens(t.input(), t.output(), t.cacheRead(), t.cacheCreation())));
        return out;
    }

    /**
     * Accepts the shared secret (any email, unscoped) or a per-account token
     * (that account's own email only). Neither being configured/valid is a
     * {@code 401}, not a {@code 503}: unlike the shared-secret-only design
     * this revises, an otherwise-unconfigured deployment can still accept
     * ingestion the moment one admin mints their own token, so there is no
     * "not configured at all" state worth a distinct status for.
     */
    private void authorize(String authorization, String requestEmail) {
        if (authorization == null || !authorization.startsWith(BEARER_PREFIX)) {
            throw ApiException.unauthorized("missing or malformed Authorization header");
        }
        String provided = authorization.substring(BEARER_PREFIX.length());

        if (properties.ingestConfigured() && secretMatches(provided, properties.ingestSecret())) {
            return;
        }

        Optional<Account> owner = tokens.resolve(provided);
        if (owner.isEmpty()) {
            throw ApiException.unauthorized("invalid ingest credential");
        }
        if (!owner.get().email().equalsIgnoreCase(requestEmail == null ? "" : requestEmail)) {
            throw ApiException.forbidden("this token may only report usage for its own account");
        }
    }

    /**
     * Constant-time compare so the secret cannot be recovered by timing the
     * endpoint — mirrors the reference tool's own {@code timingSafeEqual} use
     * for the same route.
     */
    private static boolean secretMatches(String provided, String expected) {
        return MessageDigest.isEqual(
                provided.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8));
    }
}
