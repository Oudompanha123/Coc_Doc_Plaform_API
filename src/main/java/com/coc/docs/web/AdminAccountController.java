package com.coc.docs.web;

import com.coc.docs.account.AccountService;
import com.coc.docs.account.dto.AccountSummary;
import com.coc.docs.account.AppRole;
import com.coc.docs.auth.CurrentUser;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Account administration.
 *
 * <p>Sits under a fixed {@code super_admin} floor in the filter chain, above the
 * {@code admin} floor the rest of {@code /admin/**} carries. FR-AUTH-008 makes
 * that distinction explicitly: publishing content and granting roles are
 * different kinds of authority, and an admin has only the first.
 *
 * <p>// req: FR-AUTH-003, FR-AUTH-005, FR-AUTH-008
 */
@RestController
@RequestMapping("/api/v1/admin/accounts")
public class AdminAccountController {

    private final AccountService accounts;

    public AdminAccountController(AccountService accounts) {
        this.accounts = accounts;
    }

    @GetMapping
    List<ApiDtos.AccountResponse> list() {
        return accounts.list().stream().map(ApiDtos.AccountResponse::of).toList();
    }

    /**
     * Changes a role, an activation state, or both.
     *
     * <p>A PATCH because an absent field means "leave alone". A PUT would require
     * the caller to restate every field, and a client that forgot one would
     * silently reactivate a deactivated account while changing a role.
     *
     * <p>// req: FR-AUTH-003, FR-AUTH-005
     */
    @PatchMapping("/{accountId}")
    ApiDtos.AccountResponse update(
            @PathVariable UUID accountId,
            @RequestBody ApiDtos.UpdateAccountRequest request,
            @AuthenticationPrincipal CurrentUser actor) {

        if (request.role() == null && request.active() == null) {
            throw ApiException.badRequest("supply a role, an active flag, or both");
        }

        // Parsed before anything is written, so an unknown role name is a 400
        // rather than a partially applied request.
        AppRole role = request.role() == null
                ? null
                : AppRole.parse(request.role())
                        .orElseThrow(() -> ApiException.badRequest(
                                "unknown role \"" + request.role() + "\"; expected guest, user, "
                                        + "admin or super_admin"));

        // One service call, one transaction. Applying the two fields with two
        // calls let a request that changed both commit the first and fail the
        // second, which reported an error while a role change had already landed.
        return ApiDtos.AccountResponse.of(
                accounts.update(accountId, role, request.active(), actor));
    }

    // -----------------------------------------------------------------------
    // Self-registration approval (ADR-014)
    // -----------------------------------------------------------------------

    /**
     * The queue of people waiting to be let in.
     *
     * <p>Under the same {@code super_admin} floor as the rest of this
     * controller. Deciding who gets an account is the same kind of authority as
     * granting a role, and an {@code admin} has neither (FR-AUTH-008).
     */
    @GetMapping("/pending")
    List<AccountSummary> pending() {
        return accounts.listPending().stream().map(AccountSummary::of).toList();
    }

    /** Lets a pending registration sign in, from the next request onward. */
    @PostMapping("/{accountId}/approve")
    AccountSummary approve(@PathVariable UUID accountId, @AuthenticationPrincipal CurrentUser actor) {
        return AccountSummary.of(accounts.approve(accountId, actor));
    }

    /** Refuses a pending registration. The row stays, so the decision stays auditable. */
    @PostMapping("/{accountId}/reject")
    AccountSummary reject(@PathVariable UUID accountId, @AuthenticationPrincipal CurrentUser actor) {
        return AccountSummary.of(accounts.reject(accountId, actor));
    }
}
