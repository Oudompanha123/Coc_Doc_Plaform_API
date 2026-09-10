package com.coc.docs.account.dto;

import com.coc.docs.account.Account;
import com.coc.docs.account.AccountStatus;
import com.coc.docs.account.Role;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

/**
 * An account as shown to a super_admin reviewing registrations (ADR-014).
 * Never includes {@code password_hash} — this type exists specifically so
 * that omission is structural, not a field a future edit could accidentally
 * add back to {@link com.coc.docs.auth.dto.AuthResponse}-style responses.
 */
public record AccountSummary(
        UUID id,
        String email,
        String displayName,
        Role role,
        @Schema(description = "PENDING, ACTIVE, or REJECTED — the registration/approval lifecycle, ADR-014.")
                AccountStatus status) {

    public static AccountSummary of(Account account) {
        return new AccountSummary(
                account.id(), account.email(), account.displayName(), account.role(), account.status());
    }
}
