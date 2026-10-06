package com.coc.docs.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.coc.docs.PostgresTestBase;
import com.coc.docs.auth.CurrentUser;
import com.coc.docs.auth.SessionRepository;
import com.coc.docs.web.ApiException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Deleting an account for real (ADR-023).
 *
 * <p>Against a real PostgreSQL, because every claim here is about what the
 * database does when the row goes: which references cascade, which go null,
 * and which would have refused the delete outright. A mocked repository can
 * only confirm that a method was called.
 *
 * <p>// req: FR-AUTH-003, FR-AUTH-008, ADR-023, ADR-091
 */
class AccountDeletionTest extends PostgresTestBase {

    @Autowired AccountService accountService;
    @Autowired AccountRepository accounts;
    @Autowired SessionRepository sessions;
    @Autowired JdbcClient jdbc;
    @Autowired com.coc.docs.axrenewal.AxRenewalCommentRepository commentRepository;

    private Account boss;
    private CurrentUser actor;

    @BeforeEach
    void setUp() {
        jdbc.sql("truncate audit_log, app_session, account restart identity cascade").update();
        boss = accounts.insert("boss@example.com", "Boss", AppRole.SUPER_ADMIN, null, AccountStatus.ACTIVE);
        actor = new CurrentUser(boss, accounts.findPermissions(boss.id()), new byte[] {9});
    }

    private Account member(String email) {
        return accounts.insert(email, "Member", AppRole.USER, null, AccountStatus.ACTIVE);
    }

    @Test
    @DisplayName("the row is gone, not merely flagged")
    void deleteRemovesTheRow() {
        Account victim = member("gone@example.com");

        accountService.delete(victim.id(), actor);

        assertThat(accounts.findById(victim.id())).isEmpty();
        assertThat(accounts.findByEmail("gone@example.com")).isEmpty();
        // The distinction from deactivation, which leaves the row behind.
        Long remaining = jdbc.sql("select count(*) from account where email = ?")
                .param("gone@example.com")
                .query(Long.class)
                .single();
        assertThat(remaining).isZero();
    }

    /**
     * The bug V10 fixes. {@code account_role.granted_by} was declared with no
     * ON DELETE action, so PostgreSQL refused to delete anyone who had ever
     * granted a role — which is every super_admin who ever approved a
     * colleague, the accounts most likely to need removing.
     */
    @Test
    @DisplayName("an account that granted someone else's role can still be deleted")
    void deletingAGranterIsNotBlockedByTheirGrants() {
        Account granter = accounts.insert(
                "granter@example.com", "Granter", AppRole.SUPER_ADMIN, null, AccountStatus.ACTIVE);
        Account subject = member("subject@example.com");
        accounts.grantRole(subject.id(), AppRole.ADMIN, granter.id());

        accountService.delete(granter.id(), actor);

        assertThat(accounts.findById(granter.id())).isEmpty();
        // The grant survives the granter; only the attribution goes.
        UUID grantedBy = jdbc.sql(
                        "select granted_by from account_role where account_id = ? and role_key = 'admin'::app_role")
                .param(subject.id())
                .query(UUID.class)
                .optional()
                .orElse(null);
        assertThat(grantedBy).isNull();
        assertThat(accounts.findById(subject.id()).orElseThrow().roles()).contains(AppRole.ADMIN);
    }

    @Test
    @DisplayName("sessions go with the account, so a deleted person cannot keep acting")
    void deleteRevokesSessionsByCascade() {
        Account victim = member("session@example.com");
        byte[] tokenHash = {4, 5, 6, 7};
        sessions.insert(tokenHash, victim.id(), Instant.now().plusSeconds(3600), "agent", "127.0.0.1");
        assertThat(sessions.find(tokenHash)).isPresent();

        accountService.delete(victim.id(), actor);

        assertThat(sessions.find(tokenHash)).isEmpty();
    }

    /**
     * The whole reason deletion was resisted until ADR-023. If removing an
     * account erased what they had done, the trail could not answer a question
     * about last quarter, and NFR-OPS-AUDIT would be unmeetable.
     */
    @Test
    @DisplayName("the audit trail outlives the account, still naming who acted")
    void auditEntriesSurviveWithTheirEmail() {
        Account victim = member("actor@example.com");
        CurrentUser victimActor =
                new CurrentUser(victim, accounts.findPermissions(victim.id()), new byte[] {1});
        // Something they did, recorded before they are removed.
        accountService.setActive(member("other@example.com").id(), false, victimActor);

        accountService.delete(victim.id(), actor);

        String email = jdbc.sql("select actor_email from audit_log where action = ? order by id limit 1")
                .param(com.coc.docs.audit.AuditAction.ACCOUNT_DEACTIVATE)
                .query(String.class)
                .single();
        assertThat(email).isEqualTo("actor@example.com");
        UUID actorId = jdbc.sql("select actor_id from audit_log where action = ? order by id limit 1")
                .param(com.coc.docs.audit.AuditAction.ACCOUNT_DEACTIVATE)
                .query(UUID.class)
                .optional()
                .orElse(null);
        // Nulled by the FK, which is why the email column exists at all.
        assertThat(actorId).isNull();
    }

    @Test
    @DisplayName("the deletion itself is recorded, with enough detail to be readable later")
    void deletionIsAudited() {
        Account victim = member("recorded@example.com");

        accountService.delete(victim.id(), actor);

        String detail = jdbc.sql("select detail::text from audit_log where action = ?")
                .param(com.coc.docs.audit.AuditAction.ACCOUNT_DELETE)
                .query(String.class)
                .single();
        // A UUID alone would name a row that no longer exists.
        assertThat(detail).contains("recorded@example.com").contains("user");
    }

    @Test
    @DisplayName("you cannot delete yourself")
    void deletingYourselfIsRefused() {
        assertThatThrownBy(() -> accountService.delete(boss.id(), actor))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("your own account");

        assertThat(accounts.findById(boss.id())).isPresent();
    }

    @Test
    @DisplayName("the last super admin cannot be deleted, and is told why")
    void deletingTheLastSuperAdminIsRefused() {
        Account other = accounts.insert(
                "second@example.com", "Second", AppRole.SUPER_ADMIN, null, AccountStatus.ACTIVE);
        CurrentUser otherActor =
                new CurrentUser(other, accounts.findPermissions(other.id()), new byte[] {2});
        // Leaves `other` as the only active super admin.
        accountService.delete(boss.id(), otherActor);

        CurrentUser adminActor = new CurrentUser(
                accounts.insert("plain@example.com", "Plain", AppRole.ADMIN, null, AccountStatus.ACTIVE),
                java.util.Set.of(),
                new byte[] {3});

        assertThatThrownBy(() -> accountService.delete(other.id(), adminActor))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("only active super admin");

        assertThat(accounts.findById(other.id())).isPresent();
    }

    @Test
    @DisplayName("someone who worked on AX Renewal and the docs can be deleted; the work stays, unattributed (ADR-091)")
    void deletingSomeoneWhoDidWorkKeepsTheWork() {
        Account worker = member("worker@example.com");
        UUID project = UUID.randomUUID();
        // The references V64 changed from NO ACTION, one of each kind.
        jdbc.sql("insert into ax_renewal_project (id, name, created_by) values (?, 'Legacy Portal', ?)")
                .params(project, worker.id())
                .update();
        jdbc.sql("insert into ax_renewal_comment (project_id, author_id, body) values (?, ?, 'looks right')")
                .params(project, worker.id())
                .update();
        jdbc.sql("insert into ax_renewal_confirmation (project_id, section, confirmed_by, confirmed_via) "
                        + "values (?, 'pages', ?, 'developer_role')")
                .params(project, worker.id())
                .update();
        jdbc.sql("insert into ax_renewal_implementation_run (project_id, status, started_by) values (?, 'applied', ?)")
                .params(project, worker.id())
                .update();
        jdbc.sql("insert into docs_content (product_key, slug, kind, body, updated_by) "
                        + "values ('jex3-0', '', 'hero', '{}'::jsonb, ?)")
                .param(worker.id())
                .update();

        accountService.delete(worker.id(), actor);

        assertThat(accounts.findById(worker.id())).isEmpty();
        assertThat(jdbc.sql("select created_by from ax_renewal_project where id = ?").param(project)
                        .query(UUID.class).optional())
                .isEmpty();
        assertThat(jdbc.sql("select count(*) from ax_renewal_project where id = ?").param(project)
                        .query(Long.class).single())
                .isEqualTo(1L);
        assertThat(jdbc.sql("select count(*) from ax_renewal_implementation_run where project_id = ? and started_by is null")
                        .param(project).query(Long.class).single())
                .isEqualTo(1L);
        assertThat(jdbc.sql("select count(*) from docs_content where updated_by is null").query(Long.class).single())
                .isEqualTo(1L);
        // Still in the thread, under a name that says what happened.
        assertThat(commentRepository.listForProject(project))
                .singleElement()
                .satisfies(c -> {
                    assertThat(c.authorId()).isNull();
                    assertThat(c.authorName()).isEqualTo("Deleted account");
                    assertThat(c.body()).isEqualTo("looks right");
                });
    }

    @Test
    @DisplayName("no reference to account can refuse a delete — every one says cascade or set null (ADR-091)")
    void everyReferenceToAccountSaysWhatHappensOnDelete() {
        // The guard V10 and V64 both needed and did not have: a table added
        // later with a bare "references account" makes every account that
        // touched it undeletable again, and nothing failed until someone tried.
        java.util.List<String> blocking = jdbc.sql("""
                        select c.conrelid::regclass::text || '.' || a.attname
                        from pg_constraint c
                        join pg_attribute a on a.attrelid = c.conrelid and a.attnum = c.conkey[1]
                        where c.contype = 'f'
                          and c.confrelid = 'account'::regclass
                          and c.confdeltype in ('a', 'r')
                        order by 1
                        """)
                .query(String.class)
                .list();

        assertThat(blocking).as("foreign keys to account with no ON DELETE action").isEmpty();
    }

    @Test
    @DisplayName("deleting an id that is not there is a 404, not a silent success")
    void deletingAnUnknownAccountIsNotFound() {
        assertThatThrownBy(() -> accountService.delete(UUID.randomUUID(), actor))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("account");
    }
}
