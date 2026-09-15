package com.coc.docs.account;

import java.time.Instant;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/**
 * An account row.
 *
 * <p>{@code deactivatedAt} rather than a delete, so authored content and audit
 * entries keep their attribution after someone leaves (FR-AUTH-003).
 *
 * <p><b>Two independent things can stop a sign-in</b>, and conflating them
 * would lose information an administrator needs:
 *
 * <ul>
 *   <li>{@code status} — was this registration ever approved at all? As of
 *       the 2026-09-11 revision of ADR-014, a self-registered account also
 *       starts {@link AccountStatus#ACTIVE} immediately — the approval gate
 *       ADR-014 originally described no longer applies to the register
 *       endpoint (see {@code AccountService.register}'s javadoc).
 *       {@link AccountStatus#PENDING} and {@link AccountStatus#REJECTED}
 *       remain meaningful for any account a {@code super_admin} explicitly
 *       moves into those states.
 *   <li>{@code deactivatedAt} — was an approved account later turned off?
 * </ul>
 *
 * <p>An account invited by a {@code super_admin} is {@code ACTIVE} from the
 * start; nobody has to approve what an administrator created.
 *
 * <p>There is no password field here. The hash lives on
 * {@link AccountCredential}, which is only built on the password sign-in path,
 * so it cannot ride along into session issuance, responses or the audit trail
 * where {@code Account} is passed around freely.
 *
 * <p>{@code mustChangePassword} is not a secret the way the hash is — it is an
 * account-level fact, the same shape as {@code status} or {@code
 * deactivatedAt} — so unlike the hash it does live here, and does travel with
 * {@code Account} into responses that need to act on it.
 *
 * <p>// req: FR-AUTH-002, FR-AUTH-003, FR-AUTH-005, ADR-014
 */
public record Account(
        UUID id,
        String email,
        String displayName,
        Set<AppRole> roles,
        String avatarUrl,
        /**
         * Non-null from the moment this account's owner uploads their own
         * photo (ADR-026) until they remove it. Marks {@code avatarUrl} as
         * theirs to control: {@code SessionService.refreshProfile} must not
         * overwrite it with the identity provider's {@code picture} claim on
         * a later Google/Keycloak sign-in while this is set.
         */
        Instant avatarUploadedAt,
        AccountStatus status,
        Instant deactivatedAt,
        Instant createdAt,
        Instant updatedAt,
        /**
         * True from the moment a {@code super_admin} invite sets a temporary
         * password until this account's owner replaces it. See
         * {@code AccountService.create} and {@code SessionService.changeOwnPassword}.
         */
        boolean mustChangePassword) {

    /**
     * Defensive copy. The role set backs every authorisation decision made
     * during a request; it must not change underneath one.
     */
    public Account {
        roles = (roles == null || roles.isEmpty())
                ? Collections.unmodifiableSet(EnumSet.noneOf(AppRole.class))
                : Collections.unmodifiableSet(EnumSet.copyOf(roles));
    }

    /**
     * The highest-ranked role held — what every {@code isAtLeast} check
     * compares against.
     *
     * <p><b>Highest, deliberately.</b> With a set of roles, "is the caller at
     * least admin" has to pick one. Picking the highest means holding an extra
     * low-ranked role can never take access away; the alternative would make
     * granting someone a role a potentially destructive act, which is not how
     * anyone expects a grant to behave.
     *
     * <p>Falls back to {@link AppRole#GUEST} when no role is held. That state
     * is reachable — a {@code super_admin} can revoke the last one — and it
     * means "nothing", not an exception on a hot path.
     */
    public AppRole role() {
        return roles.stream().max(Comparator.comparingInt(Enum::ordinal)).orElse(AppRole.GUEST);
    }

    /** Whether this account holds a role specifically, as opposed to out-ranking it. */
    public boolean hasRole(AppRole role) {
        return roles.contains(role);
    }

    /** False once a {@code super_admin} has deactivated the account. */
    public boolean isActive() {
        return deactivatedAt == null;
    }

    /**
     * Whether this account may sign in right now, by either path.
     *
     * <p>Both conditions, always. A pending registration that someone
     * deactivated, or an approved account since turned off, both answer no —
     * and the caller does not have to remember which check applies to which
     * sign-in route.
     */
    public boolean canSignIn() {
        return status == AccountStatus.ACTIVE && isActive();
    }

    /** A copy with a different status, for a {@code super_admin}'s approve or reject. */
    public Account withStatus(AccountStatus newStatus) {
        return new Account(
                id, email, displayName, roles, avatarUrl, avatarUploadedAt, newStatus, deactivatedAt, createdAt,
                updatedAt, mustChangePassword);
    }

    /** A copy holding a different set of roles. */
    public Account withRoles(Set<AppRole> newRoles) {
        return new Account(
                id, email, displayName, newRoles, avatarUrl, avatarUploadedAt, status, deactivatedAt, createdAt,
                updatedAt, mustChangePassword);
    }

    /** A copy with a self-uploaded photo (ADR-026) — {@code avatarUploadedAt} marks it as owner-controlled. */
    public Account withUploadedAvatar(String newAvatarUrl, Instant uploadedAt) {
        return new Account(
                id, email, displayName, roles, newAvatarUrl, uploadedAt, status, deactivatedAt, createdAt,
                updatedAt, mustChangePassword);
    }
}
