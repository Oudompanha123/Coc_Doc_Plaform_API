package com.coc.docs.axrenewal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
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

    /**
     * The project's live comments, oldest first.
     *
     * <p>{@code authorName} is joined from {@code account.display_name} at
     * read time — not a stored column, so a later name change shows up here
     * rather than being frozen at comment time.
     *
     * <p>Deleted rows are filtered out here, once, rather than returned with
     * a flag every caller has to remember to check (ADR-045). Nothing outside
     * this class needs to know they still exist — the row is kept so history
     * cannot be quietly rewritten, not so it can be read back.
     */
    public List<AxRenewalComment> listForProject(UUID projectId) {
        return jdbc.sql("""
                        select c.id, c.project_id, c.author_id, a.display_name as author_name, c.body, c.created_at
                        from ax_renewal_comment c
                        join account a on a.id = c.author_id
                        where c.project_id = ? and c.deleted_at is null
                        order by c.created_at
                        """)
                .param(projectId)
                .query(this::map)
                .list();
    }

    /** One live comment, for the authorship check a delete has to make first. */
    public Optional<AxRenewalComment> findLive(UUID projectId, UUID commentId) {
        return jdbc.sql("""
                        select c.id, c.project_id, c.author_id, a.display_name as author_name, c.body, c.created_at
                        from ax_renewal_comment c
                        join account a on a.id = c.author_id
                        where c.id = ? and c.project_id = ? and c.deleted_at is null
                        """)
                .params(commentId, projectId)
                .query(this::map)
                .optional();
    }

    /**
     * Hides one comment, recording who hid it and when.
     *
     * <p>{@code deleted_at is null} in the predicate buys idempotency and
     * race-safety in one stroke: two callers pressing delete at the same
     * moment produce one update and one {@code false}, and the second does
     * not overwrite the first person's name with their own.
     *
     * @return whether this call was the one that hid it
     */
    public boolean softDelete(UUID commentId, UUID deletedBy) {
        return jdbc.sql("""
                        update ax_renewal_comment
                        set deleted_at = now(), deleted_by = ?
                        where id = ? and deleted_at is null
                        """)
                .params(deletedBy, commentId)
                .update()
                > 0;
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
