package com.coc.docs.axrenewal;

import java.time.Instant;
import java.util.UUID;

/**
 * A row of {@code ax_renewal_confirmation} — one plan section's sign-off
 * (ADR-037). Its absence means that section is unconfirmed; there is no
 * boolean flag to fall out of step with the row's presence.
 *
 * <p>{@code confirmedVia} records which authority the confirming caller held
 * at the time — {@code super_admin} or the {@code developer} capability —
 * since either may confirm (ADR-037), and the trail should say which.
 */
public record AxRenewalConfirmation(
        UUID projectId, AxRenewalSection section, Instant confirmedAt, UUID confirmedBy, String confirmedVia) {

    public static final String VIA_SUPER_ADMIN = "super_admin";
    public static final String VIA_DEVELOPER_CAPABILITY = "developer_capability";
}
