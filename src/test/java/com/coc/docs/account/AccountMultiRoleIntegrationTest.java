package com.coc.docs.account;

import static org.assertj.core.api.Assertions.assertThat;

import com.coc.docs.PostgresTestBase;
import com.coc.docs.auth.CurrentUser;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Whether an account can genuinely hold more than one role at once, against a
 * real PostgreSQL — the question behind the "Details" popup's multi-role
 * checkboxes (`AdminAccountController.update`'s `roles` field).
 *
 * <p>Against real Postgres rather than a mocked repository, because the claim
 * under test is specifically that {@code account_role} ends up with more than
 * one physical row for the account — a mock could only confirm that
 * {@code replaceRoles} was called with the right argument, not that the
 * database actually persisted it as separate rows.
 *
 * <p>// req: ADR-016, FR-AUTH-005
 */
class AccountMultiRoleIntegrationTest extends PostgresTestBase {

    @Autowired AccountService accountService;
    @Autowired AccountRepository accounts;
    @Autowired JdbcClient jdbc;

    private CurrentUser actor;

    @BeforeEach
    void setUp() {
        jdbc.sql("truncate audit_log, app_session, account restart identity cascade").update();
        Account superAdmin =
                accounts.insert("boss@example.com", "Boss", AppRole.SUPER_ADMIN, null, AccountStatus.ACTIVE);
        actor = new CurrentUser(superAdmin, accounts.findPermissions(superAdmin.id()), new byte[] {1});
    }

    @Test
    @DisplayName("setRoles with more than one role writes one account_role row per role, not one")
    void multipleRolesBecomeMultipleRows() {
        Account member = accounts.insert("member@example.com", "Member", AppRole.USER, null, AccountStatus.ACTIVE);

        accountService.setRoles(member.id(), EnumSet.of(AppRole.USER, AppRole.ADMIN), actor);

        Long rowCount = jdbc.sql("select count(*) from account_role where account_id = ?")
                .param(member.id())
                .query(Long.class)
                .single();
        assertThat(rowCount).isEqualTo(2L);

        java.util.List<String> roleKeys = jdbc.sql("select role_key::text from account_role where account_id = ? order by role_key")
                .param(member.id())
                .query(String.class)
                .list();
        assertThat(roleKeys).containsExactlyInAnyOrder("user", "admin");

        // Re-read through the same path the API answers with, confirming the
        // set round-trips and the effective (highest-rank) role is correct.
        Account reloaded = accounts.findById(member.id()).orElseThrow();
        assertThat(reloaded.roles()).containsExactlyInAnyOrder(AppRole.USER, AppRole.ADMIN);
        assertThat(reloaded.role()).isEqualTo(AppRole.ADMIN);
    }

    @Test
    @DisplayName("replacing the set removes rows for roles no longer held")
    void narrowingTheSetRemovesTheDroppedRolesRow() {
        Account member = accounts.insert("member2@example.com", "Member Two", AppRole.USER, null, AccountStatus.ACTIVE);
        accountService.setRoles(member.id(), EnumSet.of(AppRole.USER, AppRole.ADMIN), actor);

        accountService.setRoles(member.id(), EnumSet.of(AppRole.ADMIN), actor);

        Long rowCount = jdbc.sql("select count(*) from account_role where account_id = ?")
                .param(member.id())
                .query(Long.class)
                .single();
        assertThat(rowCount).isEqualTo(1L);
        assertThat(accounts.findById(member.id()).orElseThrow().roles()).containsExactly(AppRole.ADMIN);
    }
}
