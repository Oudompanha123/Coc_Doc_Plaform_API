package com.coc.docs.axrenewal;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

/**
 * A row of {@code ax_renewal_db_change} — one planned, additive-only
 * database change (ADR-037).
 *
 * <p>{@code changeType} is structurally limited to {@link #ADD_COLUMN}/
 * {@link #ADD_INDEX} by a database check constraint, not just a service-layer
 * opinion — a table can never be created or dropped through this plan.
 */
public record AxRenewalDbChange(
        UUID id,
        UUID projectId,
        String changeType,
        String tableName,
        JsonNode detail,
        String rationale,
        int position,
        Instant updatedAt,
        UUID updatedBy) {

    public static final String ADD_COLUMN = "add_column";
    public static final String ADD_INDEX = "add_index";
}
