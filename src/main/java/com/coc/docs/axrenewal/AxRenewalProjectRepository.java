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

/** Reads and writes {@code ax_renewal_project} rows (ADR-037). */
@Repository
public class AxRenewalProjectRepository {

    private static final String COLUMNS =
            "id, name, description, status, manifest_filename, manifest_content, "
                    + "manifest_attached_at, manifest_attached_by, attach_stats::text as attach_stats, "
                    + "created_by, created_at, updated_at, revision";

    private final JdbcClient jdbc;
    private final Jsonb jsonb;

    public AxRenewalProjectRepository(JdbcClient jdbc, Jsonb jsonb) {
        this.jdbc = jdbc;
        this.jsonb = jsonb;
    }

    private AxRenewalProject map(ResultSet rs, int rowNum) throws SQLException {
        OffsetDateTime manifestAttachedAt = rs.getObject("manifest_attached_at", OffsetDateTime.class);
        String attachStats = rs.getString("attach_stats");
        return new AxRenewalProject(
                rs.getObject("id", UUID.class),
                rs.getString("name"),
                rs.getString("description"),
                rs.getString("status"),
                rs.getString("manifest_filename"),
                rs.getString("manifest_content"),
                manifestAttachedAt == null ? null : manifestAttachedAt.toInstant(),
                rs.getObject("manifest_attached_by", UUID.class),
                attachStats == null ? null : jsonb.read(attachStats),
                rs.getObject("created_by", UUID.class),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getObject("updated_at", OffsetDateTime.class).toInstant(),
                rs.getInt("revision"));
    }

    /** Every project, newest first — only ever served to a super admin (ADR-070); everyone else gets {@link #listForMember}. */
    public List<AxRenewalProject> list() {
        return jdbc.sql("select " + COLUMNS + " from ax_renewal_project order by created_at desc")
                .query(this::map)
                .list();
    }

    /**
     * The projects {@code accountId} is a member of, newest first (ADR-070).
     * Scoped in the query itself, not filtered afterwards, so a project the
     * caller is not on never leaves the database for this request.
     */
    public List<AxRenewalProject> listForMember(UUID accountId) {
        return jdbc.sql("select " + COLUMNS + " from ax_renewal_project p"
                        + " where exists (select 1 from ax_renewal_project_member m"
                        + " where m.project_id = p.id and m.account_id = ?)"
                        + " order by created_at desc")
                .param(accountId)
                .query(this::map)
                .list();
    }

    public Optional<AxRenewalProject> findById(UUID id) {
        return jdbc.sql("select " + COLUMNS + " from ax_renewal_project where id = ?")
                .param(id)
                .query(this::map)
                .optional();
    }

    public AxRenewalProject insert(String name, String description, UUID createdBy) {
        return jdbc.sql("""
                        insert into ax_renewal_project (name, description, created_by)
                        values (?, ?, ?)
                        returning
                        """ + COLUMNS)
                .params(name, description, createdBy)
                .query(this::map)
                .single();
    }

    /** Renames a project and replaces its description (ADR-075). Returns the row as it now is. */
    public AxRenewalProject updateDetails(UUID id, String name, String description) {
        return jdbc.sql("""
                        update ax_renewal_project
                        set name = ?, description = ?, updated_at = now()
                        where id = ?
                        returning
                        """ + COLUMNS)
                .params(name, description, id)
                .query(this::map)
                .single();
    }

    public void attachManifest(UUID id, String filename, String content, JsonNode stats, UUID attachedBy) {
        jdbc.sql("""
                        update ax_renewal_project
                        set manifest_filename = ?, manifest_content = ?, attach_stats = ?::jsonb,
                            manifest_attached_at = now(), manifest_attached_by = ?,
                            updated_at = now()
                        where id = ?
                        """)
                .params(filename, content, stats == null ? null : jsonb.write(stats), attachedBy, id)
                .update();
    }

    /**
     * The project's current plan revision, with its row locked for the rest
     * of the caller's transaction (ADR-067). Empty when the project does not
     * exist.
     *
     * <p>{@code for update}, not a plain read: a confirmation checked against
     * an unlocked read could interleave with a concurrent edit — read the old
     * revision, then insert its confirmation after the edit had already
     * cleared that section's — and a sign-off on content nobody had seen
     * would survive. Holding the row serialises the two instead.
     */
    public Optional<Integer> lockRevision(UUID id) {
        return jdbc.sql("select revision from ax_renewal_project where id = ? for update")
                .param(id)
                .query(Integer.class)
                .optional();
    }

    /** Bumps the plan revision by one and returns the new value (ADR-067). Call after {@link #lockRevision}. */
    public int incrementRevision(UUID id) {
        return jdbc.sql("""
                        update ax_renewal_project
                        set revision = revision + 1, updated_at = now()
                        where id = ?
                        returning revision
                        """)
                .param(id)
                .query(Integer.class)
                .single();
    }

    public void updateStatus(UUID id, String status) {
        jdbc.sql("update ax_renewal_project set status = ?, updated_at = now() where id = ?")
                .params(status, id)
                .update();
    }

    /**
     * Hard delete. Every child row (analysis, plan, confirmations, comments,
     * implementation runs, destination, database connection) cascades away
     * with it — {@code on delete cascade} throughout, V25 — so there is
     * nothing else here to clean up at the database level.
     */
    public void delete(UUID id) {
        jdbc.sql("delete from ax_renewal_project where id = ?").param(id).update();
    }
}
