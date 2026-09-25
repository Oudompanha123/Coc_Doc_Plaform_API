package com.coc.docs.axrenewal;

import com.coc.docs.web.Jsonb;
import com.fasterxml.jackson.databind.JsonNode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Reads and writes {@code ax_renewal_implementation_run} and {@code ax_renewal_generated_change} rows (ADR-037, ADR-044). */
@Repository
public class AxRenewalImplementationRepository {

    private final JdbcClient jdbc;
    private final Jsonb jsonb;

    public AxRenewalImplementationRepository(JdbcClient jdbc, Jsonb jsonb) {
        this.jdbc = jdbc;
        this.jsonb = jsonb;
    }

    /** One proposed file, as the generator hands it to the service. */
    public record GeneratedChangeInput(
            String path, String changeKind, String beforeContent, String afterContent) {}

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
                completedAt == null ? null : completedAt.toInstant(),
                rs.getString("staging_path"),
                rs.getString("failure_reason"));
    }

    private AxRenewalGeneratedChange mapChange(ResultSet rs, int rowNum) throws SQLException {
        return new AxRenewalGeneratedChange(
                rs.getObject("id", UUID.class),
                rs.getObject("run_id", UUID.class),
                rs.getString("path"),
                rs.getString("change_kind"),
                rs.getString("before_content"),
                rs.getString("after_content"),
                rs.getInt("position"));
    }

    private static final String COLUMNS =
            "id, project_id, status, before_report::text as before_report, "
                    + "after_report::text as after_report, started_at, started_by, completed_at, "
                    + "staging_path, failure_reason";

    /**
     * Any run not yet at a terminal status ({@code running}, {@code
     * generating}, {@code awaiting_review}, or {@code applying}), inlined as
     * literals — a fixed, constant set, never user input — rather than bound
     * as a JDBC array, which the plain PostgreSQL driver does not accept from
     * a bare {@code String[]}. Only ever one per project.
     */
    public Optional<AxRenewalImplementationRun> findActive(UUID projectId) {
        return jdbc.sql("select " + COLUMNS + " from ax_renewal_implementation_run "
                        + "where project_id = ? and status in ('running','generating','awaiting_review','applying') "
                        + "order by started_at desc limit 1")
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

    public Optional<AxRenewalImplementationRun> findById(UUID id) {
        return jdbc.sql("select " + COLUMNS + " from ax_renewal_implementation_run where id = ?")
                .param(id)
                .query(this::map)
                .optional();
    }

    /** Starts a run already at a terminal-bound status ({@code running}) — the original Phase-1 placeholder path. */
    public AxRenewalImplementationRun insert(UUID projectId, UUID startedBy, JsonNode beforeReport) {
        return insert(projectId, startedBy, beforeReport, AxRenewalImplementationRun.STATUS_RUNNING);
    }

    /** Starts a run at any initial status — Generate uses {@code generating} directly, skipping the placeholder's {@code running}. */
    public AxRenewalImplementationRun insert(UUID projectId, UUID startedBy, JsonNode beforeReport, String initialStatus) {
        return jdbc.sql("""
                        insert into ax_renewal_implementation_run (project_id, started_by, before_report, status)
                        values (?, ?, ?::jsonb, ?)
                        returning
                        """ + COLUMNS)
                .params(projectId, startedBy, beforeReport == null ? null : jsonb.write(beforeReport), initialStatus)
                .query(this::map)
                .single();
    }

    /** A same-run status transition that isn't terminal yet (e.g. {@code generating} → {@code awaiting_review}) — no report, no completion time. */
    public void updateStatus(UUID id, String status) {
        jdbc.sql("update ax_renewal_implementation_run set status = ? where id = ?")
                .params(status, id)
                .update();
    }

    /** Records where a Generate run's disposable working copy lives, once it exists. */
    public void markStaging(UUID id, String stagingPath) {
        jdbc.sql("update ax_renewal_implementation_run set staging_path = ? where id = ?")
                .params(stagingPath, id)
                .update();
    }

    /** Clears the staging path once its directory has actually been deleted — never left pointing at nothing. */
    public void clearStaging(UUID id) {
        jdbc.sql("update ax_renewal_implementation_run set staging_path = null where id = ?")
                .param(id)
                .update();
    }

    /** A terminal failure, in the run's own words — never a bare status with the reason left for someone to guess at. */
    public void markFailed(UUID id, String reason) {
        jdbc.sql("""
                        update ax_renewal_implementation_run
                        set status = ?, failure_reason = ?, completed_at = now()
                        where id = ?
                        """)
                .params(AxRenewalImplementationRun.STATUS_FAILED, reason, id)
                .update();
    }

    /** A terminal, deliberate stop (ADR-049) — distinct from {@link #markFailed}, the same "a real result vs. generation broke" distinction {@code rejected} already draws. */
    public void markCancelled(UUID id) {
        jdbc.sql("""
                        update ax_renewal_implementation_run
                        set status = ?, completed_at = now()
                        where id = ?
                        """)
                .params(AxRenewalImplementationRun.STATUS_CANCELLED, id)
                .update();
    }

    /** A terminal success — the placeholder path's {@code completed}, or Apply's {@code applied}/Reject's {@code rejected}. */
    public void markCompleted(UUID id, String status, JsonNode afterReport) {
        jdbc.sql("""
                        update ax_renewal_implementation_run
                        set status = ?, after_report = ?::jsonb, completed_at = now()
                        where id = ?
                        """)
                .params(status, afterReport == null ? null : jsonb.write(afterReport), id)
                .update();
    }

    // -----------------------------------------------------------------------
    // Generated changes (ADR-044)
    // -----------------------------------------------------------------------

    public void insertGeneratedChanges(UUID runId, List<GeneratedChangeInput> changes) {
        int position = 0;
        for (GeneratedChangeInput c : changes) {
            jdbc.sql("""
                            insert into ax_renewal_generated_change
                              (run_id, path, change_kind, before_content, after_content, position)
                            values (?, ?, ?, ?, ?, ?)
                            """)
                    .params(runId, c.path(), c.changeKind(), c.beforeContent(), c.afterContent(), position++)
                    .update();
        }
    }

    public List<AxRenewalGeneratedChange> findGeneratedChanges(UUID runId) {
        return jdbc.sql("""
                        select id, run_id, path, change_kind, before_content, after_content, position
                        from ax_renewal_generated_change
                        where run_id = ?
                        order by position
                        """)
                .param(runId)
                .query(this::mapChange)
                .list();
    }
}
