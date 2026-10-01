package com.coc.docs.axrenewal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code cancelImplementation}'s own preconditions (ADR-049): only a run
 * still {@code generating} can be cancelled. With a live process the runner
 * kills it; without one (ADR-073) the run is closed here, compare-and-set,
 * so a run that finished meanwhile is reported as a conflict, never
 * silently overwritten.
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
                runner,
                mock(AxRenewalLocalRunner.class),
                mock(AxRenewalTestRunner.class),
                event -> {});
    }

    private AxRenewalImplementationRun runAt(String status) {
        return runAt(status, "/tmp/staging");
    }

    private AxRenewalImplementationRun runAt(String status, String stagingPath) {
        return new AxRenewalImplementationRun(
                runId, projectId, status, null, null, Instant.EPOCH, actor.id(), null, stagingPath, null, null);
    }

    @Test
    void refuses_a_run_that_is_not_currently_generating() {
        when(implementations.findById(runId)).thenReturn(Optional.of(runAt(AxRenewalImplementationRun.STATUS_AWAITING_REVIEW)));

        assertThatThrownBy(() -> service.cancelImplementation(projectId, runId, actor))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("not currently generating");

        verify(runner, never()).cancel(runId);
    }

    // --- No live process on this backend (ADR-073) ------------------------
    // Used to be refused ("no active process to cancel"), which left a run
    // whose backend had stopped stuck at "generating" for good — and its
    // project impossible to delete.

    @Test
    void closes_a_generating_run_that_has_no_live_process_as_cancelled() throws Exception {
        Path staging = Files.createTempDirectory(AxRenewalImplementationRunner.STAGING_PREFIX);
        when(implementations.findById(runId))
                .thenReturn(Optional.of(runAt(AxRenewalImplementationRun.STATUS_GENERATING, staging.toString())));
        when(runner.cancel(runId)).thenReturn(false);
        when(implementations.endIfStatus(
                        runId,
                        AxRenewalImplementationRun.STATUS_GENERATING,
                        AxRenewalImplementationRun.STATUS_CANCELLED,
                        null))
                .thenReturn(true);

        service.cancelImplementation(projectId, runId, actor);

        verify(implementations)
                .endIfStatus(
                        runId,
                        AxRenewalImplementationRun.STATUS_GENERATING,
                        AxRenewalImplementationRun.STATUS_CANCELLED,
                        null);
        verify(implementations).clearStaging(runId);
        // No transaction in this unit test, so the after-commit delete runs at once.
        assertThat(staging).doesNotExist();
    }

    @Test
    void reports_a_conflict_when_the_run_finished_between_the_read_and_the_close() {
        when(implementations.findById(runId)).thenReturn(Optional.of(runAt(AxRenewalImplementationRun.STATUS_GENERATING)));
        when(runner.cancel(runId)).thenReturn(false);
        // Compare-and-set lost: it is no longer "generating" by the time we close it.
        when(implementations.endIfStatus(any(), any(), any(), any())).thenReturn(false);

        assertThatThrownBy(() -> service.cancelImplementation(projectId, runId, actor))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("not currently generating");

        verify(implementations, never()).clearStaging(any());
    }

    @Test
    void never_deletes_a_staging_path_that_is_not_one_of_its_own(@TempDir Path elsewhere) throws Exception {
        Path notStaging = Files.createDirectory(elsewhere.resolve("renewed-app"));
        Files.writeString(notStaging.resolve("keep.txt"), "the team's own code");
        when(implementations.findById(runId))
                .thenReturn(Optional.of(runAt(AxRenewalImplementationRun.STATUS_GENERATING, notStaging.toString())));
        when(runner.cancel(runId)).thenReturn(false);
        when(implementations.endIfStatus(any(), any(), any(), any())).thenReturn(true);

        service.cancelImplementation(projectId, runId, actor);

        assertThat(notStaging.resolve("keep.txt")).exists();
    }

    @Test
    void cancels_a_generating_run_with_a_live_process_by_killing_it_and_audits_it() {
        when(implementations.findById(runId)).thenReturn(Optional.of(runAt(AxRenewalImplementationRun.STATUS_GENERATING)));
        when(runner.cancel(runId)).thenReturn(true);

        service.cancelImplementation(projectId, runId, actor);

        verify(runner).cancel(runId);
        // The runner records "cancelled" itself as the killed process ends —
        // closing it here too would race that.
        verify(implementations, never()).endIfStatus(any(), any(), any(), any());
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
                        null,
                        null)));

        assertThatThrownBy(() -> service.cancelImplementation(projectId, runId, actor)).isInstanceOf(ApiException.class);

        verify(runner, never()).cancel(runId);
    }
}
