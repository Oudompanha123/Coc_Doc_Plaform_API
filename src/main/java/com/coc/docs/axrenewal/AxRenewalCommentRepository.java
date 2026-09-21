package com.coc.docs.axrenewal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Reads and writes {@code ax_renewal_comment} rows (ADR-037). */
@Repository
public class AxRenewalCommentRepository {

    private final JdbcClient jdbc;

    public AxRenewalCommentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private AxRenewalComment map(ResultSet rs, int rowNum) throws SQLException {
        return new AxRenewalComment(
                rs.getObject("id", UUID.class),
                rs.getObject("project_id", UUID.class),
                rs.getObject("author_id", UUID.class),
                rs.getString("author_name"),
                rs.getString("body"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant());
    }

    /** {@code authorName} is joined from {@code account.display_name} at read time — not a stored column, so a later name change shows up here rather than being frozen at comment time. */
    public List<AxRenewalComment> listForProject(UUID projectId) {
        return jdbc.sql("""
                        select c.id, c.project_id, c.author_id, a.display_name as author_name, c.body, c.created_at
                        from ax_renewal_comment c
                        join account a on a.id = c.author_id
                        where c.project_id = ?
                        order by c.created_at
                        """)
                .param(projectId)
                .query(this::map)
                .list();
    }

    /**
     * {@code authorName} is not written to the table — the caller passes the
     * posting account's own current display name (already in hand from
     * {@code CurrentUser}), so the returned record is consistent with what
     * {@link #listForProject} would resolve for this same row.
     */
    public AxRenewalComment insert(UUID projectId, UUID authorId, String authorName, String body) {
        return jdbc.sql("""
                        insert into ax_renewal_comment (project_id, author_id, body)
                        values (?, ?, ?)
                        returning id, project_id, author_id, body, created_at
                        """)
                .params(projectId, authorId, body)
                .query((rs, rowNum) -> new AxRenewalComment(
                        rs.getObject("id", UUID.class),
                        rs.getObject("project_id", UUID.class),
                        rs.getObject("author_id", UUID.class),
                        authorName,
                        rs.getString("body"),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant()))
                .single();
    }
}
