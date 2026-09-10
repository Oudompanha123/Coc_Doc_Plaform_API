package com.coc.docs.web;

import com.coc.docs.account.Account;
import com.coc.docs.account.AccountPrincipal;
import com.coc.docs.account.AccountRepository;
import com.coc.docs.account.AccountStatus;
import com.coc.docs.account.dto.AccountSummary;
import com.coc.docs.audit.AuditLogger;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Super-admin review of self-registered accounts (ADR-014, revision of
 * ADR-008): a registration is unusable until approved here. Matches
 * FR-AUTH-003's existing precedent that account provisioning is a
 * super_admin action, never something a caller grants themselves.
 *
 * // req: FR-AUTH-003, FR-AUTH-005, FR-AUTH-008, ADR-014
 */
@RestController
@RequestMapping("/api/admin/accounts")
@Tag(name = "Account administration", description = "Super-admin review of self-registered (pending) accounts, ADR-014.")
@SecurityRequirement(name = "cookieAuth")
public class AdminAccountController {

    private final AccountRepository accountRepository;
    private final AuditLogger auditLogger;

    public AdminAccountController(AccountRepository accountRepository, AuditLogger auditLogger) {
        this.accountRepository = accountRepository;
        this.auditLogger = auditLogger;
    }

    @Operation(summary = "List accounts awaiting approval")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @GetMapping("/pending")
    public List<AccountSummary> pending() {
        return accountRepository.findByStatus(AccountStatus.PENDING).stream()
                .map(AccountSummary::of)
                .toList();
    }

    @Operation(summary = "Approve a pending registration — the account may now sign in")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @PostMapping("/{id}/approve")
    public AccountSummary approve(@PathVariable UUID id, @AuthenticationPrincipal AccountPrincipal principal) {
        return decide(id, AccountStatus.ACTIVE, "account.approve", principal);
    }

    @Operation(summary = "Reject a pending registration — the account may never sign in")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @PostMapping("/{id}/reject")
    public AccountSummary reject(@PathVariable UUID id, @AuthenticationPrincipal AccountPrincipal principal) {
        return decide(id, AccountStatus.REJECTED, "account.reject", principal);
    }

    private AccountSummary decide(UUID id, AccountStatus newStatus, String auditAction, AccountPrincipal principal) {
        Account account = accountRepository
                .findById(id)
                .orElseThrow(() -> new AdminActionException(HttpStatus.NOT_FOUND, "Account not found."));
        if (account.status() != AccountStatus.PENDING) {
            throw new AdminActionException(HttpStatus.CONFLICT, "Account is not awaiting review.");
        }
        accountRepository.updateStatus(id, newStatus);
        auditLogger.log(auditAction, principal.id(), principal.email(), "account", id, Map.of());
        return AccountSummary.of(account.withStatus(newStatus));
    }
}
