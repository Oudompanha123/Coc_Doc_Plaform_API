package com.coc.docs.auth;

import com.coc.docs.account.Account;
import com.coc.docs.account.AppRole;
import com.coc.docs.account.Permission;
import com.coc.docs.capability.Capability;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/**
 * The authenticated caller for one request.
 *
 * <p>Holds the account row and the permissions its roles grant, both read at
 * the start of this request — so authority is a server-side fact rather than a
 * client assertion. Carries the session token hash as well, so sign-out can
 * revoke the exact session that made the call rather than all of the caller's
 * sessions.
 *
 * <p>Permissions are resolved per request and never stored in the session row.
 * A revoked role therefore takes effect on the caller's very next request,
 * which is the reason this system issues an opaque session token instead of a
 * JWT carrying claims (ADR-011, ADR-016).
 *
 * <p>Capabilities are resolved the same way, from {@code account_capability}
 * — a second, rank-independent grant (ADR-037). They live here rather than on
 * {@link Account} for the same reason permissions do: both are facts about
 * what this caller may do this request, not facts about the account row
 * itself, and putting them on {@code Account} would force every
 * account-only code path (the row mapper, audit payloads, {@code
 * AccountResponse}) to know about a mechanism that must stay decoupled from
 * it.
 *
 * <p>// req: FR-AUTH-005, FR-AUTH-007, TM-003, ADR-037
 */
public record CurrentUser(
        Account account, Set<Permission> permissions, Set<Capability> capabilities, byte[] sessionTokenHash) {

    public CurrentUser {
        permissions = (permissions == null || permissions.isEmpty())
                ? Collections.unmodifiableSet(EnumSet.noneOf(Permission.class))
                : Collections.unmodifiableSet(EnumSet.copyOf(permissions));
        capabilities = (capabilities == null || capabilities.isEmpty())
                ? Collections.unmodifiableSet(EnumSet.noneOf(Capability.class))
                : Collections.unmodifiableSet(EnumSet.copyOf(capabilities));
    }

    public UUID id() {
        return account.id();
    }

    public String email() {
        return account.email();
    }

    /** The highest-ranked role held. See {@link Account#role()} for why highest. */
    public AppRole role() {
        return account.role();
    }

    /** Every role held, not just the highest. */
    public Set<AppRole> roles() {
        return account.roles();
    }

    /**
     * Whether this caller meets a minimum rank.
     *
     * <p>Still the check that content gating uses: {@code page.min_role} states
     * a required rank, not a required role (FR-AUTH-006).
     */
    public boolean isAtLeast(AppRole required) {
        return account.role().isAtLeast(required);
    }

    /**
     * Whether this caller has been granted a specific capability.
     *
     * <p>Prefer this to {@link #isAtLeast} for anything that is an action
     * rather than a sensitivity tier. "May publish" is a property of the role
     * someone holds; "is at least admin" is an assumption about which roles
     * happen to be able to publish today.
     */
    public boolean can(Permission permission) {
        return permissions.contains(permission);
    }

    /**
     * Whether this caller has been granted a capability directly, independent
     * of any role they hold (ADR-037).
     */
    public boolean hasCapability(Capability capability) {
        return capabilities.contains(capability);
    }
}
