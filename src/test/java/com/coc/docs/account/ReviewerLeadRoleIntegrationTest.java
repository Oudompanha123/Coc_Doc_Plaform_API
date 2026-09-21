package com.coc.docs.account;

import static org.assertj.core.api.Assertions.assertThat;

import com.coc.docs.PostgresTestBase;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Whether {@code REVIEWER} and {@code LEAD} (ADR-039,
 * {@code V28__add_lead_reviewer_app_role_values.sql},
 * {@code V29__seed_lead_reviewer_roles.sql}) are real against a real
 * PostgreSQL, not just Java enum constants: the {@code app_role} type
 * actually accepts the new labels, {@code role} carries them at the right
 * rank, and {@code role_permission} grants exactly what was seeded.
 *
 * <p>// req: ADR-039, FR-AUTH-005, FR-AUTH-006
 */
class ReviewerLeadRoleIntegrationTest extends PostgresTestBase {

    @Autowired AccountRepository accounts;
    @Autowired RoleRepository roles;
    @Autowired JdbcClient jdbc;

    @BeforeEach
    void setUp() {
        jdbc.sql("truncate audit_log, app_session, account restart identity cascade").update();
    }

    @Test
    @DisplayName("an account can be created directly at reviewer or lead")
    void accountsCanHoldTheNewRoles() {
        Account reviewer =
                accounts.insert("reviewer@example.com", "Reviewer", AppRole.REVIEWER, null, AccountStatus.ACTIVE);
        Account lead = accounts.insert("lead@example.com", "Lead", AppRole.LEAD, null, AccountStatus.ACTIVE);

        assertThat(accounts.findById(reviewer.id()).orElseThrow().role()).isEqualTo(AppRole.REVIEWER);
        assertThat(accounts.findById(lead.id()).orElseThrow().role()).isEqualTo(AppRole.LEAD);
    }

    @Test
    @DisplayName("the role catalogue lists all six roles, in rank order, reviewer and lead between user and admin")
    void catalogueOrdersTheNewRolesCorrectly() {
        List<RoleRepository.RoleDetail> all = roles.findAll();

        assertThat(all).extracting(RoleRepository.RoleDetail::role)
                .containsExactly(
                        AppRole.GUEST,
                        AppRole.USER,
                        AppRole.REVIEWER,
                        AppRole.LEAD,
                        AppRole.ADMIN,
                        AppRole.SUPER_ADMIN);

        // rank stays strictly increasing and unique — the constraint V4 relies
        // on to make "highest rank held" unambiguous.
        assertThat(all).extracting(RoleRepository.RoleDetail::rank).isSorted();
        assertThat(all.stream().map(RoleRepository.RoleDetail::rank).distinct().count()).isEqualTo(all.size());
    }

    @Test
    @DisplayName("reviewer and lead are granted content.read and nothing more, until a feature needs more")
    void newRolesGrantOnlyContentRead() {
        RoleRepository.RoleDetail reviewer = roles.findAll().stream()
                .filter(r -> r.role() == AppRole.REVIEWER)
                .findFirst()
                .orElseThrow();
        RoleRepository.RoleDetail lead = roles.findAll().stream()
                .filter(r -> r.role() == AppRole.LEAD)
                .findFirst()
                .orElseThrow();

        assertThat(reviewer.permissions()).containsExactly(Permission.CONTENT_READ);
        assertThat(lead.permissions()).containsExactly(Permission.CONTENT_READ);
    }
}
