package com.coc.docs.axrenewal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Reads and writes {@code ax_renewal_confirmation} rows (ADR-037). A
 * section's row is present exactly when it is confirmed — there is no
 * boolean flag alongside it to fall out of step.
 */
@Repository
public class AxRenewalConfirmationRepository {

    private final JdbcClient jdbc;

    public AxRenewalConfirmationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private AxRenewalConfirmation map(ResultSet rs, int rowNum) throws SQLException {
        String sectionKey = rs.getString("section");
        return new AxRenewalConfirmation(
                rs.getObject("project_id", UUID.class),
                AxRenewalSection.parse(sectionKey)
                        .orElseThrow(() -> new IllegalStateException(
                                "ax_renewal_confirmation.section holds an unrecognised value: " + sectionKey)),
                rs.getObject("confirmed_at", OffsetDateTime.class).toInstant(),
                rs.getObject("confirmed_by", UUID.class),
                rs.getString("confirmed_via"),
                rs.getString("confirmed_by_name"));
    }

    /**
     * The confirmer's display name is joined from {@code account} at read
     * time — the same as a comment's author (ADR-045), so a later name change
     * shows up here too, and the same exposure: a display name, never an
     * email (NFR-SEC-PII).
     */
    private static final String SELECT = """
            select c.project_id, c.section, c.confirmed_at, c.confirmed_by, c.confirmed_via,
                   a.display_name as confirmed_by_name
            from ax_renewal_confirmation c
            left join account a on a.id = c.confirmed_by
            """;

    public Optional<AxRenewalConfirmation> find(UUID projectId, AxRenewalSection section) {
        return jdbc.sql(SELECT + "where c.project_id = ? and c.section = ?")
                .params(projectId, section.wire())
                .query(this::map)
                .optional();
    }

    /** Every confirmed section for this project, keyed by section — a section absent from this map is unconfirmed. */
    public Map<AxRenewalSection, AxRenewalConfirmation> findAllForProject(UUID projectId) {
        List<AxRenewalConfirmation> rows = jdbc.sql(SELECT + "where c.project_id = ?")
                .param(projectId)
                .query(this::map)
                .list();
        Map<AxRenewalSection, AxRenewalConfirmation> bySection = new LinkedHashMap<>();
        for (AxRenewalConfirmation row : rows) {
            bySection.put(row.section(), row);
        }
        return bySection;
    }

    public void upsert(UUID projectId, AxRenewalSection section, UUID confirmedBy, String confirmedVia) {
        jdbc.sql("""
                        insert into ax_renewal_confirmation (project_id, section, confirmed_by, confirmed_via)
                        values (?, ?, ?, ?)
                        on conflict (project_id, section) do update set
                          confirmed_at = now(), confirmed_by = excluded.confirmed_by,
                          confirmed_via = excluded.confirmed_via
                        """)
                .params(projectId, section.wire(), confirmedBy, confirmedVia)
                .update();
    }

    /** Removes a section's confirmation, if any. Returns rows removed, so the caller can tell whether one was actually cleared. */
    public int clear(UUID projectId, AxRenewalSection section) {
        return jdbc.sql("delete from ax_renewal_confirmation where project_id = ? and section = ?")
                .params(projectId, section.wire())
                .update();
    }
}
