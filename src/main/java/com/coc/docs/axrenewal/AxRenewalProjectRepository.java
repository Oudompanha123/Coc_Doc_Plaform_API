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
                    + "created_by, created_at, updated_at";

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
                rs.getObject("updated_at", OffsetDateTime.class).toInstant());
    }

    /** Every project, newest first. There is no per-account scoping yet — everyone at the `user` floor may read every project (ADR-037). */
    public List<AxRenewalProject> list() {
        return jdbc.sql("select " + COLUMNS + " from ax_renewal_project order by created_at desc")
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
