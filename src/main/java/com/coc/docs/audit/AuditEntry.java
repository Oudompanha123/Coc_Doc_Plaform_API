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
 * <p>{@code actorAvatarUrl}, unlike every other field here, is <b>not</b>
 * stored on this row — {@code audit_log} has no such column and never will,
 * the same reasoning that keeps {@code actorEmail} denormalised does not apply
 * to a picture. It is read live from {@code account.avatar_url} by a join at
 * query time ({@link AuditRepository}), so it shows whatever the actor's
 * current picture is, not what it was when the event happened, and it is null
 * whenever the account has none or has since been deleted — the same "cannot
 * be looked up afterwards" case {@code actorEmail} exists to avoid, accepted
 * here because a stale or missing picture degrades to initials, not to a
 * broken trail.
 *
 * <p>// req: FR-OPS-001, NFR-OPS-AUDIT, ADR-006
 */
public record AuditEntry(
        long id,
        UUID actorId,
        String actorEmail,
        String actorAvatarUrl,
        String action,
        String targetType,
        UUID targetId,
        JsonNode detail,
        Instant occurredAt) {}
