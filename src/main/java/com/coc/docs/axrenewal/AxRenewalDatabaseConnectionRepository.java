package com.coc.docs.axrenewal;

import com.coc.docs.web.Jsonb;
import com.fasterxml.jackson.databind.JsonNode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Reads and writes the single {@code ax_renewal_db_connection} row per
 * project — metadata only, no credential (ADR-037; see the table's own
 * comment for why).
 */
@Repository
public class AxRenewalDatabaseConnectionRepository {

    private final JdbcClient jdbc;
    private final Jsonb jsonb;

    public AxRenewalDatabaseConnectionRepository(JdbcClient jdbc, Jsonb jsonb) {
        this.jdbc = jdbc;
        this.jsonb = jsonb;
    }

    private AxRenewalDbConnection map(ResultSet rs, int rowNum) throws SQLException {
        OffsetDateTime connectedAt = rs.getObject("connected_at", OffsetDateTime.class);
        Object port = rs.getObject("port");
        String snapshot = rs.getString("schema_snapshot");
        return new AxRenewalDbConnection(
                rs.getObject("project_id", UUID.class),
                rs.getString("engine"),
                rs.getString("host"),
                port == null ? null : ((Number) port).intValue(),
                rs.getString("database_name"),
                rs.getString("schema_name"),
                snapshot == null ? null : jsonb.read(snapshot),
                connectedAt == null ? null : connectedAt.toInstant(),
                rs.getObject("connected_by", UUID.class));
    }

    public Optional<AxRenewalDbConnection> find(UUID projectId) {
        return jdbc.sql("""
                        select project_id, engine, host, port, database_name, schema_name,
                               schema_snapshot::text as schema_snapshot, connected_at, connected_by
                        from ax_renewal_db_connection
                        where project_id = ?
                        """)
                .param(projectId)
                .query(this::map)
                .optional();
    }

    public void save(
            UUID projectId,
            String engine,
            String host,
            Integer port,
            String databaseName,
            String schemaName,
            JsonNode schemaSnapshot,
            UUID connectedBy) {
        jdbc.sql("""
                        insert into ax_renewal_db_connection
                          (project_id, engine, host, port, database_name, schema_name,
                           schema_snapshot, connected_at, connected_by)
                        values (?, ?, ?, ?, ?, ?, ?::jsonb, now(), ?)
                        on conflict (project_id) do update set
                          engine = excluded.engine, host = excluded.host, port = excluded.port,
                          database_name = excluded.database_name, schema_name = excluded.schema_name,
                          schema_snapshot = excluded.schema_snapshot,
                          connected_at = now(), connected_by = excluded.connected_by
                        """)
                .params(
                        projectId,
                        engine,
                        host,
                        port,
                        databaseName,
                        schemaName,
                        schemaSnapshot == null ? null : jsonb.write(schemaSnapshot),
                        connectedBy)
                .update();
    }
}
