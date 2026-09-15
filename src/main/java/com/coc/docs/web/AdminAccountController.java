package com.coc.docs.web;

import com.coc.docs.account.Account;
import com.coc.docs.account.AccountService;
import com.coc.docs.account.dto.AccountSummary;
import com.coc.docs.account.AppRole;
import com.coc.docs.auth.CurrentUser;
import com.coc.docs.auth.SessionRepository;
import jakarta.validation.Valid;
import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
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
    private final SessionRepository sessions;

    public AdminAccountController(AccountService accounts, SessionRepository sessions) {
        this.accounts = accounts;
        this.sessions = sessions;
    }

    /**
     * Every account, with when each last signed in.
     *
     * <p>Two queries, not one per row: the sign-in times arrive as a single
     * grouped lookup and are joined in memory. A per-account query here is the
     * N+1 that stays invisible until the team is large enough to notice it.
     */
    @GetMapping
    List<ApiDtos.AccountResponse> list() {
        Map<UUID, Instant> lastSignIn = sessions.lastSignInByAccount();
        return accounts.list().stream()
                .map(account -> ApiDtos.AccountResponse.of(account, lastSignIn.get(account.id())))
                .toList();
    }

    /**
     * Creates an account ahead of the person's first sign-in.
     *
     * <p>No password is set: they sign in with Google and land on this row,
     * matched on email. Creating it first is what allows a role above
     * {@code user} to be waiting for them, instead of an administrator having
     * to catch them afterwards.
     *
     * <p>201 with a {@code Location} header, because this makes a new thing at
     * a new address — unlike the PATCH below, which changes one that exists.
     *
     * <p>// req: FR-AUTH-003, FR-AUTH-005, FR-AUTH-008, UC-004
     */
    @PostMapping
    ResponseEntity<ApiDtos.AccountResponse> create(
            @Valid @RequestBody ApiDtos.CreateAccountRequest request,
            @AuthenticationPrincipal CurrentUser actor) {

        // Parsed before anything is written, so an unknown role name is a 400
        // rather than a half-applied change.
        AppRole role = AppRole.parse(request.role())
                .orElseThrow(() -> ApiException.badRequest("unknown role: " + request.role()));

        Account created = accounts.create(request.email().trim(), role, actor);

        return ResponseEntity.created(URI.create("/api/v1/admin/accounts/" + created.id()))
                .body(ApiDtos.AccountResponse.of(created));
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

        if (request.role() == null && request.active() == null && request.displayName() == null) {
            throw ApiException.badRequest("supply a role, an active flag, a display name, or any combination");
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
                accounts.update(accountId, role, request.active(), request.displayName(), actor));
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

    /**
     * Lets a pending applicant in, at a role chosen in the same request.
     *
     * <p>The role is optional and defaults to {@code user} — the common case,
     * and the safest reading of an omitted field.
     *
     * <p>// req: FR-AUTH-003, FR-AUTH-005, FR-AUTH-008, ADR-017
     */
    @PostMapping("/{accountId}/approve")
    AccountSummary approve(
            @PathVariable UUID accountId,
            @RequestBody(required = false) ApiDtos.ApproveRequest request,
            @AuthenticationPrincipal CurrentUser actor) {

        String requested = request == null ? null : request.role();
        AppRole role = requested == null || requested.isBlank()
                ? AppRole.USER
                : AppRole.parse(requested)
                        .orElseThrow(() -> ApiException.badRequest("unknown role: " + requested));

        return AccountSummary.of(accounts.approve(accountId, role, actor));
    }

    /** Refuses a pending registration. The row stays, so the decision stays auditable. */
    @PostMapping("/{accountId}/reject")
    AccountSummary reject(@PathVariable UUID accountId, @AuthenticationPrincipal CurrentUser actor) {
        return AccountSummary.of(accounts.reject(accountId, actor));
    }

    /**
     * Removes an account outright (ADR-023).
     *
     * <p>{@code DELETE} rather than another PATCH flag, because this is not a
     * state the account can be in — afterwards there is no account. 204, for
     * the same reason: there is nothing left to return.
     *
     * <p>Under the same {@code super_admin} floor as everything else here. An
     * {@code admin} cannot grant a role or approve a registration
     * (FR-AUTH-008), and removing the person outright is plainly not the
     * lesser authority.
     *
     * <p>// req: FR-AUTH-003, FR-AUTH-008, ADR-023
     */
    @DeleteMapping("/{accountId}")
    ResponseEntity<Void> delete(
            @PathVariable UUID accountId, @AuthenticationPrincipal CurrentUser actor) {
        accounts.delete(accountId, actor);
        return ResponseEntity.noContent().build();
    }
}
