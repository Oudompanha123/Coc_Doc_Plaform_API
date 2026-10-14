package com.coc.docs.web;

import com.coc.docs.account.Account;
import com.coc.docs.claudeusage.ClaudeUsageDayActivity;
import com.coc.docs.claudeusage.ClaudeUsageIngestTokenService;
import com.coc.docs.claudeusage.ClaudeUsageModelTokens;
import com.coc.docs.claudeusage.ClaudeUsageProperties;
import com.coc.docs.claudeusage.ClaudeUsageScanLogService;
import com.coc.docs.claudeusage.ClaudeUsageService;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
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
 * <p><b>Two credential shapes are accepted (ADR-047, revising ADR-035 §2):</b>
 * {@code ClaudeUsageProperties}' single shared secret — unscoped, may report
 * as any email, kept only for deployments already configured with it — and a
 * per-account token minted from {@code POST /api/v1/me/usage-tokens}, which
 * {@link ClaudeUsageIngestTokenService#resolve} restricts to its own
 * account's email. {@code INGEST_URL} is required either way; {@code
 * INGEST_SECRET} is not — see {@code push-usage.mjs}'s own {@code
 * canReport} gate, unaffected by which credential shape fills it.
 *
 * <p>// req: FR-USAGE-002, NFR-SEC-CHANNEL, ADR-035, ADR-036, ADR-047
 */
@RestController
public class ClaudeUsageIngestController {

    private static final String BEARER_PREFIX = "Bearer ";

    private final ClaudeUsageService usage;
    private final ClaudeUsageProperties properties;
    private final ClaudeUsageIngestTokenService tokens;
    private final ClaudeUsageScanLogService scanLogs;

    public ClaudeUsageIngestController(
            ClaudeUsageService usage,
            ClaudeUsageProperties properties,
            ClaudeUsageIngestTokenService tokens,
            ClaudeUsageScanLogService scanLogs) {
        this.usage = usage;
        this.properties = properties;
        this.tokens = tokens;
        this.scanLogs = scanLogs;
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
                toDomain(request.byModel()),
                toDomain(request.activity()));

        return new ApiDtos.ClaudeUsageIngestResponse(outcome.action());
    }

    /**
     * A reporter's scan found nothing new to upload (its ledger said every day
     * is already reported): records only that it scanned, so the dashboard's
     * "Last scan" moves while "Last reported" keeps meaning the last upload
     * (ADR-097). Same credential check as {@link #ingest}; writes no usage.
     */
    @PostMapping("/api/ingest/scan")
    ApiDtos.ClaudeUsageIngestResponse scan(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @Valid @RequestBody ApiDtos.ClaudeUsageScanRequest request) {

        authorize(authorization, request.email());
        return new ApiDtos.ClaudeUsageIngestResponse(usage.recordScan(request.email()).action());
    }

    /**
     * One scan's record for the admin View Logs page (ADR-099): its steps,
     * counts, and where it failed. Sent by the reporter at the end of every
     * scan — a failed one too, and later, from its local queue, one that
     * failed because this server was unreachable. Same credential check as
     * {@link #ingest}; writes no usage and does not move {@code last_scan_at}.
     */
    @PostMapping("/api/ingest/scan-log")
    ApiDtos.ClaudeUsageIngestResponse scanLog(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @Valid @RequestBody ApiDtos.ClaudeUsageScanLogRequest request) {

        authorize(authorization, request.email());
        return new ApiDtos.ClaudeUsageIngestResponse(scanLogs.record(request.toReport()));
    }

    /**
     * A scan has started (ADR-107): the Scan Logs page shows it "In progress"
     * until its {@link #scanLog} result completes the same row. Sent by
     * reporters from 0.6.0, once, best-effort — never queued, since a late
     * "started" says nothing the result does not. Same credential check as
     * {@link #ingest}; writes no usage and does not move {@code last_scan_at}.
     */
    @PostMapping("/api/ingest/scan-log/start")
    ApiDtos.ClaudeUsageIngestResponse scanStart(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @Valid @RequestBody ApiDtos.ClaudeUsageScanStartRequest request) {

        authorize(authorization, request.email());
        return new ApiDtos.ClaudeUsageIngestResponse(scanLogs.recordStart(request.toStart()));
    }

    /** The wire DTO stays out of the service layer (ApiDtos.java's own stated rule) — this is the one conversion point. */
    private static Map<String, ClaudeUsageModelTokens> toDomain(Map<String, ApiDtos.ClaudeUsageModelTokensRequest> byModel) {
        if (byModel == null) {
            return Map.of();
        }
        Map<String, ClaudeUsageModelTokens> out = new LinkedHashMap<>();
        byModel.forEach((model, t) ->
                out.put(model, new ClaudeUsageModelTokens(t.input(), t.output(), t.cacheRead(), t.cacheCreation(), t.cacheCreation1h())));
        return out;
    }

    private static ClaudeUsageDayActivity toDomain(ApiDtos.ClaudeUsageActivityRequest a) {
        if (a == null) {
            return null;
        }
        List<ClaudeUsageDayActivity.Project> projects = a.projects() == null ? List.of() : a.projects().stream()
                .map(p -> p == null ? null : new ClaudeUsageDayActivity.Project(
                        p.name(), p.requestCount(), p.apiCallCount(), p.conversationCount(), p.sessionCount(),
                        p.activeSeconds(), p.input(), p.output(), p.cacheRead(), p.cacheCreation(), p.firstAt(), p.lastAt()))
                .collect(Collectors.toList());
        List<ClaudeUsageDayActivity.Session> sessions = a.sessions() == null ? List.of() : a.sessions().stream()
                .map(s -> s == null ? null : new ClaudeUsageDayActivity.Session(
                        s.startedAt(), s.endedAt(), s.requestCount(), s.projects()))
                .collect(Collectors.toList());
        return new ClaudeUsageDayActivity(
                a.requestCount(), a.apiCallCount(), a.conversationCount(), a.sessionCount(), a.activeSeconds(),
                projects, sessions);
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
