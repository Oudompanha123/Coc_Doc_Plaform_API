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
 * data under NFR-SEC-PII. {@code recent()} is now reachable, through {@code
 * AdminAuditController} (Overview's "Recent Activity" panel) — gated at the
 * {@code super_admin} floor in {@code SecurityConfig}, the same floor {@code
 * /api/v1/admin/accounts/**} carries, for the same reason: both surface
 * another account's identity, not the caller's own.
 *
 * <p><b>Any further controller added over these reads must sit at that same
 * {@code super_admin} floor.</b> The default-deny terminal rule makes
 * forgetting safe rather than open, but it would place a new audit route at
 * {@code super_admin} by accident rather than by decision.
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
                rs.getString("actor_avatar_url"),
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
                        select l.id, l.actor_id, l.actor_email, a.avatar_url as actor_avatar_url,
                               l.action, l.target_type, l.target_id,
                               l.detail::text as detail, l.occurred_at
                        from audit_log l
                        left join account a on a.id = l.actor_id
                        order by l.occurred_at desc, l.id desc
                        limit ?
                        """)
                .param(limit)
                .query(this::map)
                .list();
    }

    /**
     * The most recent entries this one account was the actor of.
     *
     * <p>The counterpart to {@link #recent(int)}, which is unscoped: this is
     * what a caller who may only see their own history gets. The filter is on
     * {@code actor_id}, not {@code actor_email} — an email can be reassigned to
     * a new account, and a feed that followed the address rather than the
     * account would show one person another person's history.
     *
     * <p>Rows whose {@code actor_id} is null (system-initiated, or an account
     * since deleted) belong to nobody and so are returned to nobody here.
     *
     * <p>// req: FR-OPS-001, NFR-SEC-PII
     */
    public List<AuditEntry> recentForActor(UUID actorId, int limit) {
        return jdbc.sql("""
                        select l.id, l.actor_id, l.actor_email, a.avatar_url as actor_avatar_url,
                               l.action, l.target_type, l.target_id,
                               l.detail::text as detail, l.occurred_at
                        from audit_log l
                        left join account a on a.id = l.actor_id
                        where l.actor_id = ?
                        order by l.occurred_at desc, l.id desc
                        limit ?
                        """)
                .params(actorId, limit)
                .query(this::map)
                .list();
    }

    /**
     * This one account's own sign-in/sign-out history — the Security card's
     * "Recent sign-ins" (ADR-093). A dedicated filtered query rather than
     * taking a larger slice of {@link #recentForActor} and filtering client
     * or service side: this account's other activity (content saves, AX
     * Renewal actions, ...) sits in the same table and would otherwise push
     * genuine sign-in events outside a fixed-size window.
     *
     * <p>// req: FR-OPS-001, NFR-SEC-PII, ADR-093
     */
    public List<AuditEntry> recentSignInsForActor(UUID actorId, int limit) {
        return jdbc.sql("""
                        select l.id, l.actor_id, l.actor_email, a.avatar_url as actor_avatar_url,
                               l.action, l.target_type, l.target_id,
                               l.detail::text as detail, l.occurred_at
                        from audit_log l
                        left join account a on a.id = l.actor_id
                        where l.actor_id = ? and l.action in (?, ?)
                        order by l.occurred_at desc, l.id desc
                        limit ?
                        """)
                .params(actorId, AuditAction.AUTH_SIGN_IN, AuditAction.AUTH_SIGN_OUT, limit)
                .query(this::map)
                .list();
    }

    public List<AuditEntry> forTarget(String targetType, UUID targetId, int limit) {
        return jdbc.sql("""
                        select l.id, l.actor_id, l.actor_email, a.avatar_url as actor_avatar_url,
                               l.action, l.target_type, l.target_id,
                               l.detail::text as detail, l.occurred_at
                        from audit_log l
                        left join account a on a.id = l.actor_id
                        where l.target_type = ? and l.target_id = ?
                        order by l.occurred_at desc, l.id desc
                        limit ?
                        """)
                .params(targetType, targetId, limit)
                .query(this::map)
                .list();
    }
}
