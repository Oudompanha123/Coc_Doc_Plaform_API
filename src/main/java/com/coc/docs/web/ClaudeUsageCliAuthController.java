package com.coc.docs.web;

import com.coc.docs.auth.CurrentUser;
import com.coc.docs.claudeusage.ClaudeUsageCliAuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The two halves of the claude-team-usage CLI's browser login (ADR-060).
 *
 * <p>{@code POST /api/v1/me/usage-tokens/cli-authorizations} is the human:
 * session-authenticated, under the existing {@code /api/v1/me/usage-tokens/**}
 * USER floor (ADR-053) — the same people who may already mint a token by
 * hand. JSON-only, so the same CSRF reasoning as every other session route
 * holds ({@code SecurityConfig}).
 *
 * <p>{@code POST /api/cli/token} is the machine: no session exists in a CLI,
 * so {@code SecurityConfig} permits it and the code + PKCE verifier checked in
 * {@link ClaudeUsageCliAuthService#exchange} is what protects it — the same
 * shape as {@code /api/ingest}, and outside {@code /api/v1/**} for the same
 * reason (a CLI in the wild should not break when the admin API is versioned).
 *
 * <p>// req: FR-USAGE-002, NFR-SEC-CHANNEL, ADR-060
 */
@RestController
public class ClaudeUsageCliAuthController {

    private final ClaudeUsageCliAuthService cliAuth;
    private final PublicIngestUrl ingestUrl;

    public ClaudeUsageCliAuthController(ClaudeUsageCliAuthService cliAuth, PublicIngestUrl ingestUrl) {
        this.cliAuth = cliAuth;
        this.ingestUrl = ingestUrl;
    }

    @PostMapping("/api/v1/me/usage-tokens/cli-authorizations")
    @ResponseStatus(HttpStatus.CREATED)
    ApiDtos.ClaudeUsageCliAuthorizeResponse authorize(
            @Valid @RequestBody ApiDtos.ClaudeUsageCliAuthorizeRequest request,
            @AuthenticationPrincipal CurrentUser caller) {
        return new ApiDtos.ClaudeUsageCliAuthorizeResponse(
                cliAuth.approve(caller, request.codeChallenge(), request.device()));
    }

    /** {@code no-store}: the body carries a bearer token and must never sit in any cache. */
    @PostMapping("/api/cli/token")
    ResponseEntity<ApiDtos.ClaudeUsageCliTokenResponse> token(
            @Valid @RequestBody ApiDtos.ClaudeUsageCliTokenRequest request, HttpServletRequest httpRequest) {
        ClaudeUsageCliAuthService.Exchanged exchanged = cliAuth.exchange(request.code(), request.codeVerifier());
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new ApiDtos.ClaudeUsageCliTokenResponse(
                        exchanged.minted().rawValue(),
                        exchanged.account().email(),
                        exchanged.account().displayName(),
                        ingestUrl.forRequest(httpRequest)));
    }
}
