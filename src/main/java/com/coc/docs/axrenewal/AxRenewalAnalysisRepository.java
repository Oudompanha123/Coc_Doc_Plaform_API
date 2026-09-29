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

/** Reads and writes the single {@code ax_renewal_analysis} row per project (ADR-037). */
@Repository
public class AxRenewalAnalysisRepository {

    private final JdbcClient jdbc;
    private final Jsonb jsonb;

    public AxRenewalAnalysisRepository(JdbcClient jdbc, Jsonb jsonb) {
        this.jdbc = jdbc;
        this.jsonb = jsonb;
    }

    private AxRenewalAnalysis map(ResultSet rs, int rowNum) throws SQLException {
        return new AxRenewalAnalysis(
                rs.getObject("project_id", UUID.class),
                jsonb.read(rs.getString("stack_detected")),
                jsonb.read(rs.getString("findings")),
                jsonb.read(rs.getString("pages_detected")),
                jsonb.read(rs.getString("indexed")),
                jsonb.read(rs.getString("running_on_today")),
                jsonb.read(rs.getString("file_layout")),
                jsonb.read(rs.getString("module_coupling")),
                jsonb.read(rs.getString("recommended_stack_move")),
                rs.getBoolean("model_connected"),
                rs.getObject("analyzed_at", OffsetDateTime.class).toInstant(),
                rs.getObject("analyzed_by", UUID.class));
    }

    public Optional<AxRenewalAnalysis> find(UUID projectId) {
        return jdbc.sql("""
                        select project_id, stack_detected::text as stack_detected,
                               findings::text as findings, pages_detected::text as pages_detected,
                               indexed::text as indexed, running_on_today::text as running_on_today,
                               file_layout::text as file_layout, module_coupling::text as module_coupling,
                               recommended_stack_move::text as recommended_stack_move,
                               model_connected, analyzed_at, analyzed_by
                        from ax_renewal_analysis
                        where project_id = ?
                        """)
                .param(projectId)
                .query(this::map)
                .optional();
    }

    /** Replaces the whole analysis, one row per project. There is only ever one — a new run supersedes the last. */
    public void upsert(
            UUID projectId,
            JsonNode stackDetected,
            JsonNode findings,
            JsonNode pagesDetected,
            JsonNode indexed,
            JsonNode runningOnToday,
            JsonNode fileLayout,
            JsonNode moduleCoupling,
            JsonNode recommendedStackMove,
            boolean modelConnected,
            UUID analyzedBy) {
        jdbc.sql("""
                        insert into ax_renewal_analysis
                          (project_id, stack_detected, findings, pages_detected, indexed, running_on_today,
                           file_layout, module_coupling, recommended_stack_move, model_connected, analyzed_by,
                           analyzed_at)
                        values (?, ?::jsonb, ?::jsonb, ?::jsonb, ?::jsonb, ?::jsonb, ?::jsonb, ?::jsonb, ?::jsonb,
                                ?, ?, now())
                        on conflict (project_id) do update set
                          stack_detected = excluded.stack_detected,
                          findings = excluded.findings,
                          pages_detected = excluded.pages_detected,
                          indexed = excluded.indexed,
                          running_on_today = excluded.running_on_today,
                          file_layout = excluded.file_layout,
                          module_coupling = excluded.module_coupling,
                          recommended_stack_move = excluded.recommended_stack_move,
                          model_connected = excluded.model_connected,
                          analyzed_by = excluded.analyzed_by,
                          analyzed_at = now()
                        """)
                .params(
                        projectId,
                        jsonb.write(stackDetected),
                        jsonb.write(findings),
                        jsonb.write(pagesDetected),
                        jsonb.write(indexed),
                        jsonb.write(runningOnToday),
                        jsonb.write(fileLayout),
                        jsonb.write(moduleCoupling),
                        jsonb.write(recommendedStackMove),
                        modelConnected,
                        analyzedBy)
                .update();
    }
}
