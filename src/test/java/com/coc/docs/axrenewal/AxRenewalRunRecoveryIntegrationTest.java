package com.coc.docs.axrenewal;

import static org.assertj.core.api.Assertions.assertThat;

import com.coc.docs.PostgresTestBase;
import com.coc.docs.account.Account;
import com.coc.docs.account.AccountRepository;
import com.coc.docs.account.AccountStatus;
import com.coc.docs.account.AppRole;
import com.coc.docs.auth.CurrentUser;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The SQL behind startup recovery and compare-and-set transitions (ADR-073),
 * against real Postgres. Skipped without Docker ({@link PostgresTestBase}).
 *
 * <p>// req: ADR-073
 */
class AxRenewalRunRecoveryIntegrationTest extends PostgresTestBase {

    @Autowired AxRenewalService service;
    @Autowired AxRenewalImplementationRepository implementations;
    @Autowired AccountRepository accounts;
    @Autowired JdbcClient jdbc;

    private UUID projectId;
    private UUID accountId;

    @BeforeEach
    void aProject() {
        Account account = accounts.insert(
                "runs-" + UUID.randomUUID() + "@example.com", "Runs", AppRole.SUPER_ADMIN, null, AccountStatus.ACTIVE);
        accountId = account.id();
        CurrentUser owner = new CurrentUser(account, accounts.findPermissions(accountId), new byte[] {3});
        projectId = service.create("Recovery " + UUID.randomUUID(), null, owner).id();
    }

    private UUID runAt(String status, Duration startedAgo) {
        return jdbc.sql("""
                        insert into ax_renewal_implementation_run (project_id, status, started_by, started_at)
                        values (?, ?, ?, now() - make_interval(secs => ?)) returning id
                        """)
                .params(projectId, status, accountId, (double) startedAgo.toSeconds())
                .query(UUID.class)
                .single();
    }

    private String statusOf(UUID runId) {
        return jdbc.sql("select status from ax_renewal_implementation_run where id = ?")
                .param(runId)
                .query(String.class)
                .single();
    }

    @Test
    void findOrphaned_returns_only_runs_that_cannot_be_alive_anywhere() {
        Duration threshold = AxRenewalImplementationRunner.GENERATING_ORPHANED_AFTER;
        UUID oldGenerating = runAt("generating", threshold.plusMinutes(5));
        UUID youngGenerating = runAt("generating", Duration.ofMinutes(2));
        UUID applying = runAt("applying", Duration.ofMinutes(1));
        UUID running = runAt("running", Duration.ofMinutes(1));
        UUID awaitingReview = runAt("awaiting_review", threshold.plusHours(5));
        UUID applied = runAt("applied", threshold.plusHours(5));

        List<UUID> found = implementations.findOrphaned(Instant.now().minus(threshold)).stream()
                .map(AxRenewalImplementationRun::id)
                .toList();

        assertThat(found).contains(oldGenerating, applying, running);
        // Possibly alive on a teammate's backend; and legitimately waiting.
        assertThat(found).doesNotContain(youngGenerating, awaitingReview, applied);
    }

    @Test
    void endIfStatus_changes_only_a_run_still_at_the_expected_status() {
        UUID run = runAt("generating", Duration.ofMinutes(1));

        assertThat(implementations.endIfStatus(run, "generating", "cancelled", null)).isTrue();
        assertThat(statusOf(run)).isEqualTo("cancelled");

        // A late runner trying to fail it afterwards changes nothing.
        assertThat(implementations.endIfStatus(run, "generating", "failed", "late")).isFalse();
        assertThat(statusOf(run)).isEqualTo("cancelled");
    }

    @Test
    void a_late_runner_cannot_move_a_closed_run_to_review_or_attach_a_staging_path() {
        UUID run = runAt("generating", Duration.ofMinutes(1));
        implementations.endIfStatus(run, "generating", "cancelled", null);

        assertThat(implementations.transitionIfStatus(run, "generating", "awaiting_review")).isFalse();
        assertThat(implementations.markStagingIfGenerating(run, "/tmp/ax-renewal-generate-x")).isFalse();
        assertThat(statusOf(run)).isEqualTo("cancelled");
    }

    @Test
    void deleteGeneratedChanges_removes_a_runs_proposed_changes() {
        UUID run = runAt("generating", Duration.ofMinutes(1));
        implementations.insertGeneratedChanges(
                run, List.of(new AxRenewalImplementationRepository.GeneratedChangeInput("a.txt", "create", null, "x")));

        implementations.deleteGeneratedChanges(run);

        assertThat(implementations.findGeneratedChanges(run)).isEmpty();
    }
}
