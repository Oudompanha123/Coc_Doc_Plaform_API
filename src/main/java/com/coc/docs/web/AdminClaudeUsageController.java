package com.coc.docs.web;

import com.coc.docs.auth.CurrentUser;
import com.coc.docs.claudeusage.ClaudeUsageMember;
import com.coc.docs.claudeusage.ClaudeUsageService;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Claude Usage dashboard's own admin surface: the rollup, and roster
 * management (invite, dashboard-scoped role, budget override, remove).
 *
 * <p>Sits under {@code /api/v1/admin/**}, already covered by {@code
 * SecurityConfig}'s generic {@code admin}-role rule for that prefix — unlike
 * {@code /api/v1/admin/accounts/**}, this is not account authority (FR-AUTH-008
 * is about granting {@code AppRole}; {@link ClaudeUsageMember#teamRole()} is a
 * dashboard-scoped label with nothing to do with it), so it needs no floor
 * above the one every other editorial admin action already carries.
 *
 * <p>// req: FR-USAGE-001, FR-USAGE-003, FR-USAGE-004, ADR-035
 */
@RestController
@RequestMapping("/api/v1/admin/claude-usage")
public class AdminClaudeUsageController {

    private final ClaudeUsageService usage;

    public AdminClaudeUsageController(ClaudeUsageService usage) {
        this.usage = usage;
    }

    /*
     * The dashboard read is not here. It moved to ClaudeUsageController, at
     * /api/v1/claude-usage, because a plain `user` has to reach their own row
     * and the generic /api/v1/admin/** floor would refuse them — the same
     * reason AX Renewal took a prefix of its own. What stays here is what only
     * an admin may do: change a dashboard role, set a budget, take somebody
     * off tracking.
     */

    /**
     * Adds someone to the tracking roster, {@code invited} until their own
     * machine reports for the first time.
     *
     * <p>201 with a {@code Location} header — this makes a new roster row at a
     * new address, unlike the mutations below, which change one that exists.
     */
    @PostMapping("/members")
    ResponseEntity<ApiDtos.ClaudeUsageMemberSummaryResponse> invite(
            @Valid @RequestBody ApiDtos.InviteClaudeUsageMemberRequest request,
            @AuthenticationPrincipal CurrentUser actor) {

        ClaudeUsageMember created = usage.invite(request.email(), request.displayName(), request.teamRole(), actor);
        return ResponseEntity.created(URI.create("/api/v1/admin/claude-usage/members/" + created.id()))
                .body(ApiDtos.ClaudeUsageMemberSummaryResponse.of(created));
    }

    /** Changes the dashboard-scoped role — never {@code AppRole}; see the class Javadoc. */
    @PatchMapping("/members/{id}")
    ApiDtos.ClaudeUsageMemberSummaryResponse updateRole(
            @PathVariable UUID id,
            @Valid @RequestBody ApiDtos.UpdateClaudeUsageMemberRoleRequest request,
            @AuthenticationPrincipal CurrentUser actor) {

        return ApiDtos.ClaudeUsageMemberSummaryResponse.of(usage.updateTeamRole(id, request.teamRole(), actor));
    }

    /**
     * Sets or clears a member's soft-budget override.
     *
     * <p>{@code PUT}, not {@code PATCH}: the body's one field is the whole
     * resource this call is about, and an explicit {@code null} unambiguously
     * means "clear it" rather than "leave alone" — the ambiguity a PATCH with
     * an optional field would have here.
     */
    @PutMapping("/members/{id}/budget")
    ApiDtos.ClaudeUsageMemberSummaryResponse updateBudget(
            @PathVariable UUID id,
            @RequestBody ApiDtos.UpdateClaudeUsageBudgetRequest request,
            @AuthenticationPrincipal CurrentUser actor) {

        return ApiDtos.ClaudeUsageMemberSummaryResponse.of(
                usage.updateBudgetOverride(id, request.budgetOverrideTokens(), actor));
    }

    /**
     * Takes a member off active tracking. Never deletes the row or their
     * usage history (ADR-035) — a {@code POST} action, not {@code DELETE},
     * because nothing here is gone afterwards.
     */
    @PostMapping("/members/{id}/remove")
    ApiDtos.ClaudeUsageMemberSummaryResponse remove(@PathVariable UUID id, @AuthenticationPrincipal CurrentUser actor) {
        return ApiDtos.ClaudeUsageMemberSummaryResponse.of(usage.remove(id, actor));
    }
}
