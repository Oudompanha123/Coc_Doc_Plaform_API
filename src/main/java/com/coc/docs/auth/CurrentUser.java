package com.coc.docs.auth;

import com.coc.docs.account.Account;
import com.coc.docs.account.AppRole;
import java.util.UUID;

/**
 * The authenticated caller for one request.
 *
 * <p>Holds the account row as it was read at the start of this request, so the
 * role is a server-side fact rather than a client assertion. Carries the session
 * token hash as well, so sign-out can revoke the exact session that made the
 * call rather than all of the caller's sessions.
 *
 * <p>// req: FR-AUTH-005, FR-AUTH-007, TM-003
 */
public record CurrentUser(Account account, byte[] sessionTokenHash) {

    public UUID id() {
        return account.id();
    }

    public String email() {
        return account.email();
    }

    public AppRole role() {
        return account.role();
    }

    /**
     * Whether this caller meets a minimum role.
     *
     * <p>Every authorisation decision goes through here or through the equivalent
     * check in the security filter chain. There is no second definition of what
     * "is an admin" means.
     */
    public boolean isAtLeast(AppRole required) {
        return account.role().isAtLeast(required);
    }
}
