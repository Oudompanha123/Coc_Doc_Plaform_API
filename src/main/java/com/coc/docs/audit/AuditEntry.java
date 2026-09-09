package com.coc.docs.audit;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

/**
 * One append-only audit row.
 *
 * <p>{@code actorEmail} is denormalised on purpose: the entry has to remain
 * readable after the account it points at is deactivated or its id set null, and
 * an audit trail that says "some deleted user" is not an audit trail.
 *
 * <p>// req: FR-OPS-001, NFR-OPS-AUDIT, ADR-006
 */
public record AuditEntry(
        long id,
        UUID actorId,
        String actorEmail,
        String action,
        String targetType,
        UUID targetId,
        JsonNode detail,
        Instant occurredAt) {}
