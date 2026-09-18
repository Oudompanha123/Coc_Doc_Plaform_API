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
                rs.getString("body"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant());
    }

    public List<AxRenewalComment> listForProject(UUID projectId) {
        return jdbc.sql("""
                        select id, project_id, author_id, body, created_at
                        from ax_renewal_comment
                        where project_id = ?
                        order by created_at
                        """)
                .param(projectId)
                .query(this::map)
                .list();
    }

    public AxRenewalComment insert(UUID projectId, UUID authorId, String body) {
        return jdbc.sql("""
                        insert into ax_renewal_comment (project_id, author_id, body)
                        values (?, ?, ?)
                        returning id, project_id, author_id, body, created_at
                        """)
                .params(projectId, authorId, body)
                .query(this::map)
                .single();
    }
}
