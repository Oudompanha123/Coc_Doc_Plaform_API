package com.coc.docs.web;

import com.coc.docs.auth.CurrentUser;
import com.coc.docs.claudeusage.ClaudeUsageService;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Claude Usage dashboard, read-only, for every signed-in role.
 *
 * <p><b>Deliberately not under {@code /api/v1/admin/**}.</b> That prefix floors
 * at {@code admin} in {@code SecurityConfig}, and a plain {@code user} has to
 * reach this: they are on the roster because they ran the reporter, and their
 * own tokens and cost are theirs to see. Same reasoning, and the same shape, as
 * the AX Renewal routes taking a prefix of their own rather than fighting that
 * floor (ADR-037).
 *
 * <p><b>How much comes back is decided from the session, not from the
 * request.</b> An {@code admin} and above gets the team; everybody else gets
 * their own row, and {@link ClaudeUsageService#dashboardFor} narrows the roster
 * and the daily rows before anything is rolled up, so every figure on the
 * screen — totals, chart, active users, near limit — is about the same set of
 * people. There is no scope parameter here for a caller to widen, which is the
 * same property {@link NotificationFeedController} relies on.
 *
 * <p>Writing stays in {@link AdminClaudeUsageController}: a dashboard role, a
 * budget override and taking somebody off tracking are all admin decisions, and
 * none of them moved.
 *
 * <p>No {@code @PreAuthorize}: ADR-011 keeps one enforcement point, so the
 * floor for this route is the line in {@code SecurityConfig} and nowhere else.
 *
 * <p>// req: FR-USAGE-001, FR-USAGE-002, NFR-SEC-PII, ADR-035
 */
@RestController
@RequestMapping("/api/v1/claude-usage")
public class ClaudeUsageController {

    private final ClaudeUsageService usage;

    public ClaudeUsageController(ClaudeUsageService usage) {
        this.usage = usage;
    }

    /**
     * Everything the screen renders for one period: per-member rollup, the
     * daily chart, and totals — one call, so the pieces cannot disagree.
     *
     * @param period {@code day}, {@code week}, or {@code month}; defaults to
     *     {@code week} for anything else.
     * @param offset periods back from the current one, 0 or negative. Clamped
     *     server-side rather than rejected — see {@code ClaudeUsageService}.
     */
    @Operation(
            summary = "The Claude Usage dashboard for one period",
            description = "An admin and above sees the whole team; every other role sees only their own row. "
                    + "Read-only — the roster mutations are under /api/v1/admin/claude-usage.")
    @GetMapping
    ApiDtos.ClaudeUsageDashboardResponse dashboard(
            @RequestParam(defaultValue = "week") String period,
            @RequestParam(defaultValue = "0") int offset,
            @AuthenticationPrincipal CurrentUser caller) {

        return ApiDtos.ClaudeUsageDashboardResponse.of(usage.dashboardFor(caller, period, offset));
    }
}
