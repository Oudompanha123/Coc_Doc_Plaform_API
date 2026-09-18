package com.coc.docs.integration;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Append-only writes to, and reads from, {@code integration_delivery} (V23, ADR-037). */
@Repository
public class IntegrationDeliveryRepository {

    private final JdbcClient jdbc;

    public IntegrationDeliveryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private IntegrationDelivery map(ResultSet rs, int rowNum) throws SQLException {
        Object httpStatus = rs.getObject("http_status");
        Object latencyMs = rs.getObject("latency_ms");
        return new IntegrationDelivery(
                rs.getLong("id"),
                rs.getObject("integration_id", UUID.class),
                rs.getString("event_type"),
                httpStatus == null ? null : ((Number) httpStatus).intValue(),
                rs.getBoolean("success"),
                rs.getString("error"),
                latencyMs == null ? null : ((Number) latencyMs).intValue(),
                rs.getObject("attempted_at", OffsetDateTime.class).toInstant());
    }

    public IntegrationDelivery append(
            UUID integrationId, String eventType, Integer httpStatus, boolean success, String error, Integer latencyMs) {

        return jdbc.sql("""
                        insert into integration_delivery (integration_id, event_type, http_status, success, error, latency_ms)
                        values (?, ?, ?, ?, ?, ?)
                        returning id, integration_id, event_type, http_status, success, error, latency_ms, attempted_at
                        """)
                .params(integrationId, eventType, httpStatus, success, error, latencyMs)
                .query(this::map)
                .single();
    }

    /** Most recent attempts for one integration, newest first — what the admin UI shows per row. */
    public List<IntegrationDelivery> recentFor(UUID integrationId, int limit) {
        return jdbc.sql("""
                        select id, integration_id, event_type, http_status, success, error, latency_ms, attempted_at
                        from integration_delivery
                        where integration_id = ?
                        order by attempted_at desc, id desc
                        limit ?
                        """)
                .params(integrationId, limit)
                .query(this::map)
                .list();
    }
}
