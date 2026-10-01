package com.coc.docs.axrenewal;

import java.time.Instant;
import java.util.UUID;

/**
 * A row of {@code ax_renewal_confirmation} — one plan section's sign-off
 * (ADR-037). Its absence means that section is unconfirmed; there is no
 * boolean flag to fall out of step with the row's presence.
 *
 * <p>{@code confirmedVia} records which authority the confirming caller held
 * at the time — {@code super_admin} or the {@code developer} role (renamed
 * from the retired {@code developer} capability by ADR-039; {@code V32}
 * migrates any stored {@code developer_capability} value) — since either may
 * confirm, and the trail should say which.
 *
 * <p>{@code confirmedByName} is the confirmer's display name, read at query
 * time, so the plan can say "Confirmed by Dara" — never an email.
 */
public record AxRenewalConfirmation(
        UUID projectId,
        AxRenewalSection section,
        Instant confirmedAt,
        UUID confirmedBy,
        String confirmedVia,
        String confirmedByName) {

    public static final String VIA_SUPER_ADMIN = "super_admin";
    public static final String VIA_DEVELOPER_ROLE = "developer_role";
}
