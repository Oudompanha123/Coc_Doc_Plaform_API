package com.coc.docs.account;

import java.time.Instant;
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
 *   <li>{@code status} — was this registration ever approved at all? A
 *       self-registered account starts {@link AccountStatus#PENDING} and only a
 *       {@code super_admin} moves it on (ADR-014).
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
 * <p>// req: FR-AUTH-002, FR-AUTH-003, FR-AUTH-005, ADR-014
 */
public record Account(
        UUID id,
        String email,
        String displayName,
        AppRole role,
        AccountStatus status,
        Instant deactivatedAt,
        Instant createdAt,
        Instant updatedAt) {

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
        return new Account(id, email, displayName, role, newStatus, deactivatedAt, createdAt, updatedAt);
    }
}
