package com.coc.docs.auth.dto;

import com.coc.docs.account.Account;
import com.coc.docs.account.AccountPrincipal;
import com.coc.docs.account.Role;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

/** Returned by both {@code POST /api/auth/login} and {@code GET /api/auth/me}. */
public record AuthResponse(
        UUID id,
        String email,
        String displayName,
        @Schema(description = "One of GUEST, USER, ADMIN, SUPER_ADMIN — the role ladder, FR-AUTH-005.")
                Role role) {

    public static AuthResponse of(Account account) {
        return new AuthResponse(account.id(), account.email(), account.displayName(), account.role());
    }

    public static AuthResponse of(AccountPrincipal principal) {
        return new AuthResponse(principal.id(), principal.email(), principal.displayName(), principal.role());
    }
}
