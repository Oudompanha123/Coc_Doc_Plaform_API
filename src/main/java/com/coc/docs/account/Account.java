package com.coc.docs.account;

import java.util.UUID;

/**
 * An account row, as needed by the login/session path. Full CRUD (create,
 * deactivate, role assignment — FR-AUTH-003/004) belongs to the later
 * accounts epic (DEV-PLAN.md E8); this login API only ever reads, besides
 * the self-registration path added by ADR-014.
 */
public record Account(
        UUID id, String email, String displayName, Role role, AccountStatus status, boolean deactivated) {

    /** Whether this account may sign in right now, by either sign-in path (ADR-014). */
    public boolean canSignIn() {
        return status == AccountStatus.ACTIVE && !deactivated;
    }

    /** A copy with a different status — used after a super_admin approves or rejects a registration. */
    public Account withStatus(AccountStatus newStatus) {
        return new Account(id, email, displayName, role, newStatus, deactivated);
    }
}
