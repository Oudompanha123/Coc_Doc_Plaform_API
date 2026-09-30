package com.coc.docs.axrenewal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.coc.docs.account.AppRole;
import com.coc.docs.audit.AuditRepository;
import com.coc.docs.auth.CurrentUser;
import com.coc.docs.auth.TestCallers;
import com.coc.docs.web.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Running tests against a disposable copy of a Generate run's staged changes
 * (ADR-060 Phase 2) — only meaningful while the run is still {@code
 * awaiting_review}, since that is the only time a staging copy still exists
 * to test. The real result (executed or an honest refusal) is {@link
 * AxRenewalTestRunner}'s own job; this only guards the precondition and
 * records that it happened.
 */
class AxRenewalRunTestsTest {

    private AxRenewalProjectRepository projects;
    private AxRenewalImplementationRepository implementations;
    private AxRenewalTestRunner testRunner;
    private AxRenewalService service;

    private final UUID projectId = UUID.randomUUID();
    private final UUID runId = UUID.randomUUID();
    private final CurrentUser actor = TestCallers.user(AppRole.SUPER_ADMIN);

    @BeforeEach
    void setUp() {
        projects = mock(AxRenewalProjectRepository.class);
        implementations = mock(AxRenewalImplementationRepository.class);
        testRunner = mock(AxRenewalTestRunner.class);

        when(projects.findById(projectId)).thenReturn(Optional.of(new AxRenewalProject(
                projectId,
                "Counter App",
                null,
                AxRenewalProject.STATUS_IMPLEMENTING,
                null,
                null,
                null,
                null,
                null,
                null,
                Instant.EPOCH,
                Instant.EPOCH,
                0)));

        service = new AxRenewalService(
                projects,
                mock(AxRenewalAnalysisRepository.class),
                mock(AxRenewalPlanRepository.class),
                mock(AxRenewalDatabaseConnectionRepository.class),
                mock(AxRenewalDestinationRepository.class),
                mock(AxRenewalConfirmationRepository.class),
                implementations,
                mock(AxRenewalCommentRepository.class),
                TestMembers.everyone(),
                mock(com.coc.docs.account.AccountRepository.class),
                mock(AuditRepository.class),
                new ObjectMapper(),
                mock(LocalClaudeAnalyzer.class),
                mock(LocalClaudeChat.class),
                new AxRenewalProperties(null, null),
                mock(AxRenewalCodeGenerator.class),
                mock(AxRenewalImplementationRunner.class),
                mock(AxRenewalLocalRunner.class),
                testRunner);
    }

    private AxRenewalImplementationRun runAt(String status, String stagingPath) {
        return new AxRenewalImplementationRun(
                runId, projectId, status, null, null, Instant.EPOCH, actor.id(), null, stagingPath, null, null);
    }

    @Test
    void refuses_a_run_that_is_not_awaiting_review() {
        when(implementations.findById(runId))
                .thenReturn(Optional.of(runAt(AxRenewalImplementationRun.STATUS_APPLIED, null)));

        assertThatThrownBy(() -> service.runTests(projectId, runId, actor))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("not awaiting review");
    }

    @Test
    void runs_against_the_run_s_own_staging_path_and_returns_the_real_result() {
        when(implementations.findById(runId))
                .thenReturn(Optional.of(runAt(AxRenewalImplementationRun.STATUS_AWAITING_REVIEW, "/tmp/staging-xyz")));
        AxRenewalTestRunner.Result expected = new AxRenewalTestRunner.Result(true, 0, List.of("PASS"), null);
        when(testRunner.run("/tmp/staging-xyz")).thenReturn(expected);

        AxRenewalTestRunner.Result result = service.runTests(projectId, runId, actor);

        assertThat(result).isEqualTo(expected);
    }

    @Test
    void an_honest_refusal_from_the_runner_is_returned_as_is_not_thrown() {
        when(implementations.findById(runId))
                .thenReturn(Optional.of(runAt(AxRenewalImplementationRun.STATUS_AWAITING_REVIEW, "/tmp/staging-xyz")));
        AxRenewalTestRunner.Result notExecuted =
                new AxRenewalTestRunner.Result(false, null, List.of(), "this project's package.json has no \"test\" script to run");
        when(testRunner.run(eq("/tmp/staging-xyz"))).thenReturn(notExecuted);

        AxRenewalTestRunner.Result result = service.runTests(projectId, runId, actor);

        assertThat(result.executed()).isFalse();
        assertThat(result.note()).contains("no \"test\" script");
    }
}
