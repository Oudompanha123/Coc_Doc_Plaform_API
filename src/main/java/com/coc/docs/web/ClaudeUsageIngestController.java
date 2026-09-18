package com.coc.docs.web;

import com.coc.docs.claudeusage.ClaudeUsageModelTokens;
import com.coc.docs.claudeusage.ClaudeUsageProperties;
import com.coc.docs.claudeusage.ClaudeUsageService;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
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
 * <p><b>Authenticated by a single shared bearer secret, not a session.</b> This
 * is the one deliberate exception to this backend's whole authorization model
 * (ADR-011: every other route is enforced from a session the caller cannot
 * forge). {@code SecurityConfig} {@code permitAll()}s this path for exactly
 * that reason — the check below, not the filter chain, is what protects it —
 * and per {@code NFR-SEC-CHANNEL}'s own wording this is explicitly not a
 * template for an editorial write path. See ADR-035 decision 2 for why a
 * single shared secret, not a per-member token.
 *
 * <p>// req: FR-USAGE-002, NFR-SEC-CHANNEL, ADR-035, ADR-036
 */
@RestController
public class ClaudeUsageIngestController {

    private static final String BEARER_PREFIX = "Bearer ";

    private final ClaudeUsageService usage;
    private final ClaudeUsageProperties properties;

    public ClaudeUsageIngestController(ClaudeUsageService usage, ClaudeUsageProperties properties) {
        this.usage = usage;
        this.properties = properties;
    }

    @PostMapping("/api/ingest")
    ApiDtos.ClaudeUsageIngestResponse ingest(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @Valid @RequestBody ApiDtos.ClaudeUsageIngestRequest request) {

        authorize(authorization);

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

    private void authorize(String authorization) {
        if (!properties.ingestConfigured()) {
            // Not the caller's fault -- nobody has set app.claude-usage.ingest-secret
            // on this deployment yet. 503, not 401, says so.
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "usage ingestion is not configured on this deployment");
        }
        if (authorization == null || !authorization.startsWith(BEARER_PREFIX)) {
            throw ApiException.unauthorized("missing or malformed Authorization header");
        }
        String provided = authorization.substring(BEARER_PREFIX.length());
        if (!secretMatches(provided, properties.ingestSecret())) {
            throw ApiException.unauthorized("invalid ingest secret");
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
