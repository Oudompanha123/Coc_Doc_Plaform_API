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

/** Reads and writes {@code ax_renewal_implementation_run} rows (ADR-037). */
@Repository
public class AxRenewalImplementationRepository {

    private final JdbcClient jdbc;
    private final Jsonb jsonb;

    public AxRenewalImplementationRepository(JdbcClient jdbc, Jsonb jsonb) {
        this.jdbc = jdbc;
        this.jsonb = jsonb;
    }

    private AxRenewalImplementationRun map(ResultSet rs, int rowNum) throws SQLException {
        OffsetDateTime completedAt = rs.getObject("completed_at", OffsetDateTime.class);
        String before = rs.getString("before_report");
        String after = rs.getString("after_report");
        return new AxRenewalImplementationRun(
                rs.getObject("id", UUID.class),
                rs.getObject("project_id", UUID.class),
                rs.getString("status"),
                before == null ? null : jsonb.read(before),
                after == null ? null : jsonb.read(after),
                rs.getObject("started_at", OffsetDateTime.class).toInstant(),
                rs.getObject("started_by", UUID.class),
                completedAt == null ? null : completedAt.toInstant());
    }

    private static final String COLUMNS =
            "id, project_id, status, before_report::text as before_report, "
                    + "after_report::text as after_report, started_at, started_by, completed_at";

    public Optional<AxRenewalImplementationRun> findActive(UUID projectId) {
        return jdbc.sql("select " + COLUMNS + " from ax_renewal_implementation_run "
                        + "where project_id = ? and status = 'running' order by started_at desc limit 1")
                .param(projectId)
                .query(this::map)
                .optional();
    }

    public Optional<AxRenewalImplementationRun> findLatest(UUID projectId) {
        return jdbc.sql("select " + COLUMNS + " from ax_renewal_implementation_run "
                        + "where project_id = ? order by started_at desc limit 1")
                .param(projectId)
                .query(this::map)
                .optional();
    }

    public AxRenewalImplementationRun insert(UUID projectId, UUID startedBy, JsonNode beforeReport) {
        return jdbc.sql("""
                        insert into ax_renewal_implementation_run (project_id, started_by, before_report)
                        values (?, ?, ?::jsonb)
                        returning
                        """ + COLUMNS)
                .params(projectId, startedBy, beforeReport == null ? null : jsonb.write(beforeReport))
                .query(this::map)
                .single();
    }

    public void markCompleted(UUID id, String status, JsonNode afterReport) {
        jdbc.sql("""
                        update ax_renewal_implementation_run
                        set status = ?, after_report = ?::jsonb, completed_at = now()
                        where id = ?
                        """)
                .params(status, afterReport == null ? null : jsonb.write(afterReport), id)
                .update();
    }
}
