package com.coc.docs.axrenewal;

import com.coc.docs.web.Jsonb;
import com.fasterxml.jackson.databind.JsonNode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
                rs.getString("failure_reason"),
                rs.getString("confirmed_plan_hash"),
                rs.getString("run_kind"),
                rs.getString("instruction"));
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
                    + "staging_path, failure_reason, confirmed_plan_hash, run_kind, instruction";

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

    /** Starts a run already at a terminal-bound status ({@code running}) — the original Phase-1 placeholder path, which never has a confirmed-plan hash (ADR-056). */
    public AxRenewalImplementationRun insert(UUID projectId, UUID startedBy, JsonNode beforeReport) {
        return insert(projectId, startedBy, beforeReport, AxRenewalImplementationRun.STATUS_RUNNING, null);
    }

    /**
     * Starts a run at any initial status, with the confirmed-plan hash it
     * was built from (ADR-056) — Generate uses {@code generating} directly,
     * skipping the placeholder's {@code running}; Replay uses {@code
     * awaiting_review} directly, skipping generation entirely, carrying the
     * same hash as the applied run it reproduces. Always {@link
     * AxRenewalImplementationRun#RUN_KIND_PLAN} with no instruction — both
     * callers of this overload (fresh Generate, Replay) only ever build a
     * plan-kind run; {@link #insert(UUID, UUID, JsonNode, String, String,
     * String, String)} is the one that takes a kind explicitly.
     */
    public AxRenewalImplementationRun insert(
            UUID projectId, UUID startedBy, JsonNode beforeReport, String initialStatus, String confirmedPlanHash) {
        return insert(
                projectId,
                startedBy,
                beforeReport,
                initialStatus,
                confirmedPlanHash,
                AxRenewalImplementationRun.RUN_KIND_PLAN,
                null);
    }

    /**
     * The general form (ADR-062 Phase 2): every field {@link #insert(UUID,
     * UUID, JsonNode, String, String)} defaults, spelled out — used by {@link
     * AxRenewalService#beginGeneration} for all three run kinds.
     */
    public AxRenewalImplementationRun insert(
            UUID projectId,
            UUID startedBy,
            JsonNode beforeReport,
            String initialStatus,
            String confirmedPlanHash,
            String runKind,
            String instruction) {
        return jdbc.sql("""
                        insert into ax_renewal_implementation_run
                          (project_id, started_by, before_report, status, confirmed_plan_hash, run_kind, instruction)
                        values (?, ?, ?::jsonb, ?, ?, ?, ?)
                        returning
                        """ + COLUMNS)
                .params(
                        projectId,
                        startedBy,
                        beforeReport == null ? null : jsonb.write(beforeReport),
                        initialStatus,
                        confirmedPlanHash,
                        runKind,
                        instruction)
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

    /**
     * Every staging directory any of this project's runs still points at
     * (ADR-072). Normally none: a staging copy is removed when its run fails,
     * is cancelled, approved or rejected, and a project cannot be deleted
     * while a run is still awaiting review. This is the sweep for the case
     * where the backend stopped between creating one and cleaning it up, so
     * deleting the project does not leave its generated code behind on disk.
     */
    public List<String> findStagingPaths(UUID projectId) {
        return jdbc.sql("""
                        select staging_path from ax_renewal_implementation_run
                        where project_id = ? and staging_path is not null
                        """)
                .param(projectId)
                .query(String.class)
                .list();
    }

    /** Clears the staging path once its directory has actually been deleted — never left pointing at nothing. */
    public void clearStaging(UUID id) {
        jdbc.sql("update ax_renewal_implementation_run set staging_path = null where id = ?")
                .param(id)
                .update();
    }

    // -----------------------------------------------------------------------
    // Compare-and-set transitions (ADR-073)
    //
    // The dev database is shared by several developers' backends, each with
    // its own Generate runner. A run's row can therefore be closed (cancelled,
    // or recovered as interrupted) by a backend other than the one whose
    // process is still working on it. Every transition the runner makes
    // therefore states the status it expects to find, and does nothing —
    // returning false — if the row has already moved on. Whoever closes a run
    // first wins; a late runner discards its own result instead of
    // overwriting the outcome somebody else already recorded.
    // -----------------------------------------------------------------------

    /** {@link #markStaging}, but only while the run is still {@code generating}. */
    public boolean markStagingIfGenerating(UUID id, String stagingPath) {
        return jdbc.sql("""
                        update ax_renewal_implementation_run set staging_path = ?
                        where id = ? and status = 'generating'
                        """)
                .params(stagingPath, id)
                .update() > 0;
    }

    /** Moves a run from {@code from} to the non-terminal {@code to} (e.g. {@code awaiting_review}), only if it is still at {@code from}. */
    public boolean transitionIfStatus(UUID id, String from, String to) {
        return jdbc.sql("update ax_renewal_implementation_run set status = ? where id = ? and status = ?")
                .params(to, id, from)
                .update() > 0;
    }

    /**
     * Ends a run — {@code failed} or {@code cancelled} — only if it is still
     * at {@code from}. {@code failureReason} may be null (a cancel has none).
     */
    public boolean endIfStatus(UUID id, String from, String to, String failureReason) {
        return jdbc.sql("""
                        update ax_renewal_implementation_run
                        set status = ?, failure_reason = ?, completed_at = now()
                        where id = ? and status = ?
                        """)
                .params(to, failureReason, id, from)
                .update() > 0;
    }

    /** Removes a run's proposed changes — for a run that was closed while its runner was still producing them. */
    public void deleteGeneratedChanges(UUID runId) {
        jdbc.sql("delete from ax_renewal_generated_change where run_id = ?").param(runId).update();
    }

    /**
     * Runs that cannot still be in progress anywhere (ADR-073):
     * <ul>
     *   <li>{@code running} or {@code applying} at all — both are set and
     *       finished inside one transaction, so a <em>committed</em> row in
     *       either status only exists if that transaction's backend died
     *       half way; and
     *   <li>{@code generating} that started before {@code generatingStartedBefore}
     *       — longer ago than any real Generate attempt can last.
     * </ul>
     */
    public List<AxRenewalImplementationRun> findOrphaned(Instant generatingStartedBefore) {
        return jdbc.sql("select " + COLUMNS + " from ax_renewal_implementation_run "
                        + "where status in ('running','applying') "
                        + "or (status = 'generating' and started_at < ?) "
                        + "order by started_at")
                .param(OffsetDateTime.ofInstant(generatingStartedBefore, ZoneOffset.UTC))
                .query(this::map)
                .list();
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

    /**
     * A specific applied run, undone on purpose (ADR-062 §revert). Leaves
     * {@code completed_at} exactly as Apply set it — that timestamp is when
     * this run was actually applied, and overwriting it here would lose that
     * fact rather than record the new one; who reverted it and when is the
     * audit log's job, not a second timestamp on this row.
     */
    public void markReverted(UUID id) {
        jdbc.sql("update ax_renewal_implementation_run set status = ? where id = ?")
                .params(AxRenewalImplementationRun.STATUS_REVERTED, id)
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
