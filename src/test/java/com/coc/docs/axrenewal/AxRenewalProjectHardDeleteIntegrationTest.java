package com.coc.docs.axrenewal;

import static org.assertj.core.api.Assertions.assertThat;

import com.coc.docs.PostgresTestBase;
import com.coc.docs.account.Account;
import com.coc.docs.account.AccountRepository;
import com.coc.docs.account.AccountStatus;
import com.coc.docs.account.AppRole;
import com.coc.docs.audit.AuditAction;
import com.coc.docs.auth.CurrentUser;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Deleting an AX Renewal project removes it from the database for good
 * (ADR-051, ADR-072) — against real Postgres, because what makes it
 * permanent is the schema's own {@code on delete cascade} chain, which a
 * mocked repository cannot show.
 *
 * <p>Every table that holds a project's data gets a row first, so a table
 * added later without a cascade would make this fail rather than leave rows
 * behind silently. Skipped without Docker ({@link PostgresTestBase}).
 *
 * <p>// req: ADR-051, ADR-072
 */
class AxRenewalProjectHardDeleteIntegrationTest extends PostgresTestBase {

    @Autowired AxRenewalService service;
    @Autowired AccountRepository accounts;
    @Autowired JdbcClient jdbc;

    private CurrentUser owner;
    private UUID projectId;
    private UUID runId;

    /** Every table keyed by {@code project_id} — the project's whole footprint. */
    private static final List<String> PROJECT_TABLES = List.of(
            "ax_renewal_analysis",
            "ax_renewal_stack_layer",
            "ax_renewal_architecture_plan",
            "ax_renewal_page_mapping",
            "ax_renewal_db_change",
            "ax_renewal_db_connection",
            "ax_renewal_destination",
            "ax_renewal_confirmation",
            "ax_renewal_implementation_run",
            "ax_renewal_comment",
            "ax_renewal_project_member");

    @BeforeEach
    void aProjectWithSomethingInEveryTable() {
        Account account = accounts.insert(
                "owner-" + UUID.randomUUID() + "@example.com", "Owner", AppRole.SUPER_ADMIN, null, AccountStatus.ACTIVE);
        owner = new CurrentUser(account, accounts.findPermissions(account.id()), new byte[] {7});

        // Through the service, so the creator's member row is the real one.
        projectId = service.create("Legacy Portal", "to be deleted", owner).id();
        UUID by = account.id();

        jdbc.sql("insert into ax_renewal_analysis (project_id, analyzed_by) values (?, ?)").params(projectId, by).update();
        jdbc.sql("insert into ax_renewal_stack_layer (project_id, layer_key, updated_by) values (?, 'frontend', ?)")
                .params(projectId, by).update();
        jdbc.sql("insert into ax_renewal_architecture_plan (project_id, summary, updated_by) values (?, 'x', ?)")
                .params(projectId, by).update();
        jdbc.sql("insert into ax_renewal_page_mapping (project_id, old_path, updated_by) values (?, '/old', ?)")
                .params(projectId, by).update();
        jdbc.sql("""
                        insert into ax_renewal_db_change (project_id, change_type, table_name, detail, updated_by)
                        values (?, 'add_index', 'orders', '{}'::jsonb, ?)
                        """)
                .params(projectId, by).update();
        jdbc.sql("insert into ax_renewal_db_connection (project_id, engine, connected_by) values (?, 'postgres', ?)")
                .params(projectId, by).update();
        jdbc.sql("insert into ax_renewal_destination (project_id, kind, target, updated_by) values (?, 'git_repo', 'repo', ?)")
                .params(projectId, by).update();
        jdbc.sql("""
                        insert into ax_renewal_confirmation (project_id, section, confirmed_by, confirmed_via)
                        values (?, 'pages', ?, 'super_admin')
                        """)
                .params(projectId, by).update();
        // A finished run — an active one would (rightly) refuse the delete.
        runId = jdbc.sql("""
                        insert into ax_renewal_implementation_run (project_id, status, started_by)
                        values (?, 'applied', ?) returning id
                        """)
                .params(projectId, by)
                .query(UUID.class)
                .single();
        jdbc.sql("""
                        insert into ax_renewal_generated_change (run_id, path, change_kind, after_content)
                        values (?, 'src/App.tsx', 'modify', 'export {}')
                        """)
                .param(runId).update();
        // A soft-deleted comment too: "deleted" there means hidden, and the
        // project's delete must still take the row itself.
        jdbc.sql("insert into ax_renewal_comment (project_id, author_id, body) values (?, ?, 'live')")
                .params(projectId, by).update();
        jdbc.sql("""
                        insert into ax_renewal_comment (project_id, author_id, body, deleted_at, deleted_by)
                        values (?, ?, 'hidden', now(), ?)
                        """)
                .params(projectId, by, by).update();

        for (String table : PROJECT_TABLES) {
            assertThat(rowsFor(table)).as("seeded %s", table).isPositive();
        }
    }

    @Test
    @DisplayName("every row of the project is gone from every table, not flagged or hidden")
    void deleteRemovesEveryRow() {
        service.delete(projectId, owner);

        assertThat(count("select count(*) from ax_renewal_project where id = ?", projectId)).isZero();
        for (String table : PROJECT_TABLES) {
            assertThat(rowsFor(table)).as("rows left in %s", table).isZero();
        }
        assertThat(count("select count(*) from ax_renewal_generated_change where run_id = ?", runId))
                .as("generated changes of the project's runs")
                .isZero();
    }

    @Test
    @DisplayName("no AX Renewal table is left holding a reference to the deleted project")
    void noTableStillReferencesTheProject() {
        service.delete(projectId, owner);

        // Every column named project_id in the schema, found from the
        // catalogue rather than listed by hand — so a table added later is
        // checked too, cascade or not.
        List<Map<String, Object>> columns = jdbc.sql("""
                        select table_name from information_schema.columns
                        where table_schema = current_schema() and column_name = 'project_id'
                          and table_name like 'ax\\_renewal\\_%'
                        """)
                .query()
                .listOfRows();
        assertThat(columns).isNotEmpty();
        for (Map<String, Object> column : columns) {
            assertThat(rowsFor((String) column.get("table_name")))
                    .as("rows left in %s", column.get("table_name"))
                    .isZero();
        }
    }

    @Test
    @DisplayName("the audit entry recording the deletion is the one thing kept")
    void theDeletionItselfStaysAudited() {
        service.delete(projectId, owner);

        // audit_log is append-only and retained (ADR-006): it keeps the fact
        // that the project was deleted, and by whom — not the project.
        assertThat(count(
                        "select count(*) from audit_log where action = '" + AuditAction.AX_RENEWAL_PROJECT_DELETE
                                + "' and target_id = ?",
                        projectId))
                .isEqualTo(1);
    }

    private long rowsFor(String table) {
        return count("select count(*) from " + table + " where project_id = ?", projectId);
    }

    private long count(String sql, UUID id) {
        return jdbc.sql(sql).param(id).query(Long.class).single();
    }
}
