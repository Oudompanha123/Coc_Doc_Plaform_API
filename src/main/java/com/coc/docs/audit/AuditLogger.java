package com.coc.docs.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Types;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Writes to the single, append-only {@code audit_log} table. ADR-006's
 * immutability is enforced by application discipline: this class exposes
 * {@code log(...)} and nothing else — there is no update/delete method to
 * accidentally call. // req: FR-OPS-001, FR-OPS-002, FR-OPS-003, NFR-OPS-AUDIT
 *
 * <p>Deliberately not auth-specific: every future module (content, media,
 * accounts) writes through this same class, per ADR-006's "one table, not
 * per-feature tables" decision.
 */
@Component
public class AuditLogger {

    private static final String INSERT =
            "insert into audit_log (actor_id, actor_email, action, target_type, target_id, detail) "
                    + "values (?, ?, ?, ?, ?, ?::jsonb)";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public AuditLogger(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * @param actorId nullable — a failed sign-in attempt for an email with no
     *     matching account has no actor to attribute (UC-004 E-1).
     * @param actorEmail never null; denormalised so the row still names who
     *     attempted the action even if the account is later deactivated, or
     *     never existed at all. Pass {@code ""} when even the attempted
     *     email is unknown (e.g. the request never got far enough to decode
     *     one).
     * @param targetId nullable, for the same reason as {@code actorId}.
     */
    public void log(String action, UUID actorId, String actorEmail, String targetType, UUID targetId,
            Map<String, Object> detail) {
        String json;
        try {
            json = objectMapper.writeValueAsString(detail == null ? Map.of() : detail);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialise audit detail for action '" + action + "'", e);
        }
        // Explicit argTypes: a null UUID bound via the plain varargs overload
        // is ambiguous to the driver (it cannot infer "uuid" from a null
        // Object) and fails against columns typed `uuid`. Types.OTHER lets
        // pgjdbc resolve the real column type at bind time.
        jdbcTemplate.update(
                INSERT,
                new Object[] {actorId, actorEmail, action, targetType, targetId, json},
                new int[] {Types.OTHER, Types.VARCHAR, Types.VARCHAR, Types.VARCHAR, Types.OTHER, Types.VARCHAR});
    }
}
