package com.coc.docs.audit;

import com.coc.docs.web.Jsonb;
import com.fasterxml.jackson.databind.JsonNode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Append-only writes to {@code audit_log}, and reads for the operations view.
 *
 * <p>There is deliberately no update or delete method on this class. The schema
 * revokes those grants from {@code public} as well, so the append-only property
 * does not rest on nobody adding a method here later.
 *
 * <p>Every row carries {@code actor_email}, denormalised so the trail stays
 * readable after an account is deactivated — which makes this table personal
 * data under NFR-SEC-PII. It satisfies that requirement today by being
 * unreachable: the read methods below exist, but no controller calls them, so
 * there is no route through which an audit row can leave the service.
 *
 * <p>That is a property of the current wiring, not a guarantee. <b>A controller
 * added over these reads must sit behind the {@code super_admin} floor</b>
 * ({@code /api/v1/admin/accounts/**} in {@code SecurityConfig}), alongside the
 * other route that returns other people's identities. The default-deny terminal
 * rule makes forgetting safe rather than open, but it would place an audit route
 * at {@code super_admin} by accident rather than by decision.
 *
 * <p>// req: FR-OPS-001, NFR-OPS-AUDIT, NFR-SEC-PII, ADR-006
 */
@Repository
public class AuditRepository {

    private final JdbcClient jdbc;
    private final Jsonb jsonb;

    public AuditRepository(JdbcClient jdbc, Jsonb jsonb) {
        this.jdbc = jdbc;
        this.jsonb = jsonb;
    }

    private AuditEntry map(ResultSet rs, int rowNum) throws SQLException {
        return new AuditEntry(
                rs.getLong("id"),
                rs.getObject("actor_id", UUID.class),
                rs.getString("actor_email"),
                rs.getString("action"),
                rs.getString("target_type"),
                rs.getObject("target_id", UUID.class),
                jsonb.read(rs.getString("detail")),
                rs.getObject("occurred_at", OffsetDateTime.class).toInstant());
    }

    public void append(
            UUID actorId,
            String actorEmail,
            String action,
            String targetType,
            UUID targetId,
            JsonNode detail) {

        jdbc.sql("""
                        insert into audit_log
                          (actor_id, actor_email, action, target_type, target_id, detail)
                        values (?, ?, ?, ?, ?, ?::jsonb)
                        """)
                .params(actorId, actorEmail, action, targetType, targetId, jsonb.write(detail))
                .update();
    }

    public List<AuditEntry> recent(int limit) {
        return jdbc.sql("""
                        select id, actor_id, actor_email, action, target_type, target_id,
                               detail::text as detail, occurred_at
                        from audit_log
                        order by occurred_at desc, id desc
                        limit ?
                        """)
                .param(limit)
                .query(this::map)
                .list();
    }

    public List<AuditEntry> forTarget(String targetType, UUID targetId, int limit) {
        return jdbc.sql("""
                        select id, actor_id, actor_email, action, target_type, target_id,
                               detail::text as detail, occurred_at
                        from audit_log
                        where target_type = ? and target_id = ?
                        order by occurred_at desc, id desc
                        limit ?
                        """)
                .params(targetType, targetId, limit)
                .query(this::map)
                .list();
    }
}
