package com.coc.docs.axrenewal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Reads and writes the single {@code ax_renewal_destination} row per project (ADR-037). */
@Repository
public class AxRenewalDestinationRepository {

    private final JdbcClient jdbc;

    public AxRenewalDestinationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private AxRenewalDestination map(ResultSet rs, int rowNum) throws SQLException {
        return new AxRenewalDestination(
                rs.getObject("project_id", UUID.class),
                rs.getString("kind"),
                rs.getString("target"),
                rs.getString("branch"),
                rs.getString("notes"),
                rs.getObject("updated_at", OffsetDateTime.class).toInstant(),
                rs.getObject("updated_by", UUID.class));
    }

    public Optional<AxRenewalDestination> find(UUID projectId) {
        return jdbc.sql("""
                        select project_id, kind, target, branch, notes, updated_at, updated_by
                        from ax_renewal_destination
                        where project_id = ?
                        """)
                .param(projectId)
                .query(this::map)
                .optional();
    }

    public void save(UUID projectId, String kind, String target, String branch, String notes, UUID updatedBy) {
        jdbc.sql("""
                        insert into ax_renewal_destination (project_id, kind, target, branch, notes, updated_by)
                        values (?, ?, ?, ?, ?, ?)
                        on conflict (project_id) do update set
                          kind = excluded.kind, target = excluded.target, branch = excluded.branch,
                          notes = excluded.notes, updated_by = excluded.updated_by, updated_at = now()
                        """)
                .params(projectId, kind, target, branch, notes, updatedBy)
                .update();
    }
}
