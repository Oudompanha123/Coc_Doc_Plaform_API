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

/**
 * Reads and writes the four plan-section tables — stack layers, architecture,
 * page mappings, database changes (ADR-037). Grouped into one repository
 * because they are always read together as "the plan", the same reasoning
 * {@code AccountRepository} joins {@code account}/{@code account_role} in one
 * statement rather than splitting them.
 */
@Repository
public class AxRenewalPlanRepository {

    /** One layer's target, as the service receives it from a request. */
    public record StackLayerInput(String layerKey, String oldTarget, String newTarget) {}

    /** One page correspondence, as the service receives it from a request. */
    public record PageMappingInput(String oldPath, String newPath, String notes) {}

    /** One database change, as the service receives it from a request. */
    public record DbChangeInput(String changeType, String tableName, JsonNode detail, String rationale) {}

    private final JdbcClient jdbc;
    private final Jsonb jsonb;

    public AxRenewalPlanRepository(JdbcClient jdbc, Jsonb jsonb) {
        this.jdbc = jdbc;
        this.jsonb = jsonb;
    }

    // -----------------------------------------------------------------------
    // Stack layers
    // -----------------------------------------------------------------------

    private AxRenewalStackLayer mapStackLayer(ResultSet rs, int rowNum) throws SQLException {
        return new AxRenewalStackLayer(
                rs.getObject("id", UUID.class),
                rs.getObject("project_id", UUID.class),
                rs.getString("layer_key"),
                rs.getString("old_target"),
                rs.getString("new_target"),
                rs.getInt("position"),
                rs.getObject("updated_at", OffsetDateTime.class).toInstant(),
                rs.getObject("updated_by", UUID.class));
    }

    public List<AxRenewalStackLayer> findStackLayers(UUID projectId) {
        return jdbc.sql("""
                        select id, project_id, layer_key, old_target, new_target, position, updated_at, updated_by
                        from ax_renewal_stack_layer
                        where project_id = ?
                        order by position
                        """)
                .param(projectId)
                .query(this::mapStackLayer)
                .list();
    }

    /** Delete-then-insert in one transaction, same pattern as {@code AccountRepository.replaceRoles}. */
    public void replaceStackLayers(UUID projectId, List<StackLayerInput> layers, UUID updatedBy) {
        jdbc.sql("delete from ax_renewal_stack_layer where project_id = ?").param(projectId).update();
        int position = 0;
        for (StackLayerInput layer : layers) {
            jdbc.sql("""
                            insert into ax_renewal_stack_layer
                              (project_id, layer_key, old_target, new_target, position, updated_by)
                            values (?, ?, ?, ?, ?, ?)
                            """)
                    .params(projectId, layer.layerKey(), layer.oldTarget(), layer.newTarget(), position++, updatedBy)
                    .update();
        }
    }

    // -----------------------------------------------------------------------
    // Architecture
    // -----------------------------------------------------------------------

    private AxRenewalArchitecturePlan mapArchitecture(ResultSet rs, int rowNum) throws SQLException {
        return new AxRenewalArchitecturePlan(
                rs.getObject("project_id", UUID.class),
                rs.getString("summary"),
                rs.getString("diagram") == null ? null : jsonb.read(rs.getString("diagram")),
                rs.getObject("updated_at", OffsetDateTime.class).toInstant(),
                rs.getObject("updated_by", UUID.class));
    }

    public Optional<AxRenewalArchitecturePlan> findArchitecture(UUID projectId) {
        return jdbc.sql("""
                        select project_id, summary, diagram::text as diagram, updated_at, updated_by
                        from ax_renewal_architecture_plan
                        where project_id = ?
                        """)
                .param(projectId)
                .query(this::mapArchitecture)
                .optional();
    }

    public void saveArchitecture(UUID projectId, String summary, JsonNode diagram, UUID updatedBy) {
        jdbc.sql("""
                        insert into ax_renewal_architecture_plan (project_id, summary, diagram, updated_by)
                        values (?, ?, ?::jsonb, ?)
                        on conflict (project_id) do update set
                          summary = excluded.summary, diagram = excluded.diagram,
                          updated_by = excluded.updated_by, updated_at = now()
                        """)
                .params(projectId, summary, diagram == null ? null : jsonb.write(diagram), updatedBy)
                .update();
    }

    // -----------------------------------------------------------------------
    // Page mappings
    // -----------------------------------------------------------------------

    private AxRenewalPageMapping mapPageMapping(ResultSet rs, int rowNum) throws SQLException {
        return new AxRenewalPageMapping(
                rs.getObject("id", UUID.class),
                rs.getObject("project_id", UUID.class),
                rs.getString("old_path"),
                rs.getString("new_path"),
                rs.getString("notes"),
                rs.getInt("position"),
                rs.getObject("updated_at", OffsetDateTime.class).toInstant(),
                rs.getObject("updated_by", UUID.class));
    }

    public List<AxRenewalPageMapping> findPageMappings(UUID projectId) {
        return jdbc.sql("""
                        select id, project_id, old_path, new_path, notes, position, updated_at, updated_by
                        from ax_renewal_page_mapping
                        where project_id = ?
                        order by position
                        """)
                .param(projectId)
                .query(this::mapPageMapping)
                .list();
    }

    public void replacePageMappings(UUID projectId, List<PageMappingInput> mappings, UUID updatedBy) {
        jdbc.sql("delete from ax_renewal_page_mapping where project_id = ?").param(projectId).update();
        int position = 0;
        for (PageMappingInput mapping : mappings) {
            jdbc.sql("""
                            insert into ax_renewal_page_mapping
                              (project_id, old_path, new_path, notes, position, updated_by)
                            values (?, ?, ?, ?, ?, ?)
                            """)
                    .params(
                            projectId,
                            mapping.oldPath(),
                            mapping.newPath(),
                            mapping.notes(),
                            position++,
                            updatedBy)
                    .update();
        }
    }

    // -----------------------------------------------------------------------
    // Database changes
    // -----------------------------------------------------------------------

    private AxRenewalDbChange mapDbChange(ResultSet rs, int rowNum) throws SQLException {
        return new AxRenewalDbChange(
                rs.getObject("id", UUID.class),
                rs.getObject("project_id", UUID.class),
                rs.getString("change_type"),
                rs.getString("table_name"),
                jsonb.read(rs.getString("detail")),
                rs.getString("rationale"),
                rs.getInt("position"),
                rs.getObject("updated_at", OffsetDateTime.class).toInstant(),
                rs.getObject("updated_by", UUID.class));
    }

    public List<AxRenewalDbChange> findDbChanges(UUID projectId) {
        return jdbc.sql("""
                        select id, project_id, change_type, table_name, detail::text as detail,
                               rationale, position, updated_at, updated_by
                        from ax_renewal_db_change
                        where project_id = ?
                        order by position
                        """)
                .param(projectId)
                .query(this::mapDbChange)
                .list();
    }

    /**
     * Delete-then-insert, same as the other plan sections. The database check
     * constraint on {@code change_type} is what actually enforces "additive
     * only" — this method never issues a table create/drop regardless of
     * what {@code changeType} is asked for, because there is no code path
     * here that could (ADR-037).
     */
    public void replaceDbChanges(UUID projectId, List<DbChangeInput> changes, UUID updatedBy) {
        jdbc.sql("delete from ax_renewal_db_change where project_id = ?").param(projectId).update();
        int position = 0;
        for (DbChangeInput change : changes) {
            jdbc.sql("""
                            insert into ax_renewal_db_change
                              (project_id, change_type, table_name, detail, rationale, position, updated_by)
                            values (?, ?, ?, ?::jsonb, ?, ?, ?)
                            """)
                    .params(
                            projectId,
                            change.changeType(),
                            change.tableName(),
                            jsonb.write(change.detail()),
                            change.rationale(),
                            position++,
                            updatedBy)
                    .update();
        }
    }
}
