package com.coc.docs.web;

import com.coc.docs.auth.CurrentUser;
import com.coc.docs.claudeusage.ClaudeUsageService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The personal "My Claude Usage" screen's own surface (ADR-052) — every
 * signed-in account's self-service view of its own Claude Code usage, unlike
 * {@link AdminClaudeUsageController}'s team-wide roster, which stays
 * {@code admin}/{@code super_admin} only.
 *
 * <p>Deliberately its own controller and prefix (not folded into {@code
 * AdminClaudeUsageController} or moved under {@code /api/v1/admin/**}): the
 * two surfaces have different authorization floors ({@code USER} here, {@code
 * ADMIN} there), and reusing one controller for both would put a
 * team-wide-read-authorization rule and a self-service one in the same class,
 * one prefix rule away from silently swapping.
 *
 * <p>Sits at {@code /api/v1/claude-usage/me} (not {@code /api/v1/me/**}, the
 * existing self-service family) because {@code /api/v1/me/usage-tokens/**}
 * already uses that prefix for a different, {@code ADMIN}-floored concept
 * (ADR-044) — a second, differently-floored meaning under the same prefix
 * would be confusing to read in {@code SecurityConfig}.
 *
 * <p>// req: FR-USAGE-005, ADR-052, ADR-058
 */
@RestController
@RequestMapping("/api/v1/claude-usage")
public class ClaudeUsageMeController {

    private final ClaudeUsageService usage;

    public ClaudeUsageMeController(ClaudeUsageService usage) {
        this.usage = usage;
    }

    /**
     * The caller's own dashboard — today's tokens and the last 30 days'
     * history. {@code actor} is resolved from the authenticated session by
     * Spring Security, never from a request parameter, so there is no
     * {@code userId} a caller could substitute to read someone else's usage.
     *
     * <p>{@code days} picks the "Usage by Model" window — 1, 7 or 30
     * (ADR-058); anything else falls back to 7 rather than a 400. It only
     * changes the window over the caller's own rows, never whose rows.
     */
    @GetMapping("/me")
    ApiDtos.ClaudeUsageMyDashboardResponse me(
            @AuthenticationPrincipal CurrentUser actor, @RequestParam(defaultValue = "7") int days) {
        return ApiDtos.ClaudeUsageMyDashboardResponse.of(usage.myDashboard(actor, days));
    }
}
