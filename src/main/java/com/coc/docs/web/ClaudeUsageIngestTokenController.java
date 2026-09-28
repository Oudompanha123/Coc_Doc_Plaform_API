package com.coc.docs.web;

import com.coc.docs.auth.CurrentUser;
import com.coc.docs.claudeusage.ClaudeUsageIngestTokenService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Self-service {@code /api/ingest} bearer tokens — any signed-in account
 * mints their own, downloads a personalized `push-usage.mjs` already filled
 * in with it, and can revoke it later without affecting anyone else's.
 * {@code SecurityConfig} floors every route here at {@code USER} (widened
 * from {@code ADMIN} by ADR-052 — V35's own migration comment still says
 * "each admin / super_admin", which is stale; see ADR-052).
 *
 * <p>Session-authenticated ({@code SessionAuthenticationFilter}), unlike
 * {@link ClaudeUsageIngestController}: that controller is the machine
 * (a reporter script) proving it may write; this one is the human deciding
 * which machines get to.
 *
 * <p>// req: FR-USAGE-002, NFR-SEC-CHANNEL, ADR-050, ADR-052
 */
@RestController
@RequestMapping("/api/v1/me/usage-tokens")
public class ClaudeUsageIngestTokenController {

    private final ClaudeUsageIngestTokenService tokens;

    public ClaudeUsageIngestTokenController(ClaudeUsageIngestTokenService tokens) {
        this.tokens = tokens;
    }

    @GetMapping
    List<ApiDtos.ClaudeUsageIngestTokenResponse> list(@AuthenticationPrincipal CurrentUser caller) {
        return tokens.list(caller).stream().map(ApiDtos.ClaudeUsageIngestTokenResponse::of).toList();
    }

    /** The label names the machine ("MacBook Pro") — shown back so the owner knows which token to revoke later. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    ApiDtos.ClaudeUsageIngestTokenCreateResponse create(
            @Valid @RequestBody ApiDtos.ClaudeUsageIngestTokenCreateRequest request,
            @AuthenticationPrincipal CurrentUser caller) {
        return ApiDtos.ClaudeUsageIngestTokenCreateResponse.of(tokens.create(caller, request.label()));
    }

    @DeleteMapping("/{id}")
    ResponseEntity<Void> revoke(@PathVariable UUID id, @AuthenticationPrincipal CurrentUser caller) {
        tokens.revoke(caller, id);
        return ResponseEntity.noContent().build();
    }
}
