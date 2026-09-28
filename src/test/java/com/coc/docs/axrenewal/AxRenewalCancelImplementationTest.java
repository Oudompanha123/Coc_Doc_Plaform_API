package com.coc.docs.axrenewal;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.coc.docs.account.AppRole;
import com.coc.docs.audit.AuditRepository;
import com.coc.docs.auth.CurrentUser;
import com.coc.docs.auth.TestCallers;
import com.coc.docs.web.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@code cancelImplementation}'s own preconditions (ADR-049): only a run
 * still {@code generating} can be cancelled, and the runner must actually
 * have had something to kill — never a silent no-op reported as success.
 */
class AxRenewalCancelImplementationTest {

    private AxRenewalProjectRepository projects;
    private AxRenewalImplementationRepository implementations;
    private AxRenewalImplementationRunner runner;
    private AxRenewalService service;

    private final UUID projectId = UUID.randomUUID();
    private final UUID runId = UUID.randomUUID();
    private final CurrentUser actor = TestCallers.user(AppRole.SUPER_ADMIN);

    @BeforeEach
    void setUp() {
        projects = mock(AxRenewalProjectRepository.class);
        implementations = mock(AxRenewalImplementationRepository.class);
        runner = mock(AxRenewalImplementationRunner.class);

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
                Instant.EPOCH)));

        service = new AxRenewalService(
                projects,
                mock(AxRenewalAnalysisRepository.class),
                mock(AxRenewalPlanRepository.class),
                mock(AxRenewalDatabaseConnectionRepository.class),
                mock(AxRenewalDestinationRepository.class),
                mock(AxRenewalConfirmationRepository.class),
                implementations,
                mock(AxRenewalCommentRepository.class),
                mock(AuditRepository.class),
                new ObjectMapper(),
                mock(LocalClaudeAnalyzer.class),
                mock(LocalClaudeChat.class),
                new AxRenewalProperties(null, null, null),
                mock(AxRenewalCodeGenerator.class),
                runner,
                mock(AxRenewalLocalRunner.class));
    }

    private AxRenewalImplementationRun runAt(String status) {
        return new AxRenewalImplementationRun(
                runId, projectId, status, null, null, Instant.EPOCH, actor.id(), null, "/tmp/staging", null);
    }

    @Test
    void refuses_a_run_that_is_not_currently_generating() {
        when(implementations.findById(runId)).thenReturn(Optional.of(runAt(AxRenewalImplementationRun.STATUS_AWAITING_REVIEW)));

        assertThatThrownBy(() -> service.cancelImplementation(projectId, runId, actor))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("not currently generating");

        verify(runner, never()).cancel(runId);
    }

    @Test
    void reports_a_conflict_rather_than_a_silent_success_when_the_runner_has_nothing_to_kill() {
        when(implementations.findById(runId)).thenReturn(Optional.of(runAt(AxRenewalImplementationRun.STATUS_GENERATING)));
        when(runner.cancel(runId)).thenReturn(false);

        assertThatThrownBy(() -> service.cancelImplementation(projectId, runId, actor))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("no active process to cancel");
    }

    @Test
    void cancels_a_generating_run_and_audits_it() {
        when(implementations.findById(runId)).thenReturn(Optional.of(runAt(AxRenewalImplementationRun.STATUS_GENERATING)));
        when(runner.cancel(runId)).thenReturn(true);

        service.cancelImplementation(projectId, runId, actor);

        verify(runner).cancel(runId);
    }

    @Test
    void refuses_a_run_belonging_to_a_different_project() {
        when(implementations.findById(runId))
                .thenReturn(Optional.of(new AxRenewalImplementationRun(
                        runId,
                        UUID.randomUUID(),
                        AxRenewalImplementationRun.STATUS_GENERATING,
                        null,
                        null,
                        Instant.EPOCH,
                        actor.id(),
                        null,
                        "/tmp/staging",
                        null)));

        assertThatThrownBy(() -> service.cancelImplementation(projectId, runId, actor)).isInstanceOf(ApiException.class);

        verify(runner, never()).cancel(runId);
    }
}
