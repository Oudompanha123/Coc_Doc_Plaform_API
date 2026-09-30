package com.coc.docs.axrenewal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
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
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Starting an error-fix or UI-change Generate run (ADR-062 Phase 2) —
 * deliberately bypasses every Plan-section precondition {@code
 * startImplementation} enforces, but still requires a real failure/a real
 * description, an applied filesystem destination, a configured local model,
 * and nothing else already active. Both operate on the destination itself as
 * their source, never the legacy checkout.
 */
class AxRenewalErrorFixAndUiChangeTest {

    private AxRenewalProjectRepository projects;
    private AxRenewalImplementationRepository implementations;
    private AxRenewalDestinationRepository destinations;
    private AxRenewalLocalRunner localRunner;
    private AxRenewalCodeGenerator generator;
    private AxRenewalService service;

    private final UUID projectId = UUID.randomUUID();
    private final CurrentUser actor = TestCallers.user(AppRole.SUPER_ADMIN);

    @BeforeEach
    void setUp() {
        projects = mock(AxRenewalProjectRepository.class);
        implementations = mock(AxRenewalImplementationRepository.class);
        destinations = mock(AxRenewalDestinationRepository.class);
        localRunner = mock(AxRenewalLocalRunner.class);
        generator = mock(AxRenewalCodeGenerator.class);

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
        when(implementations.findActive(projectId)).thenReturn(Optional.empty());

        service = new AxRenewalService(
                projects,
                mock(AxRenewalAnalysisRepository.class),
                mock(AxRenewalPlanRepository.class),
                mock(AxRenewalDatabaseConnectionRepository.class),
                destinations,
                mock(AxRenewalConfirmationRepository.class),
                implementations,
                mock(AxRenewalCommentRepository.class),
                mock(AuditRepository.class),
                new ObjectMapper(),
                mock(LocalClaudeAnalyzer.class),
                mock(LocalClaudeChat.class),
                new AxRenewalProperties(null, null),
                generator,
                mock(AxRenewalImplementationRunner.class),
                localRunner,
                mock(AxRenewalTestRunner.class));
    }

    private void appliedDestination() {
        when(implementations.findLatest(projectId)).thenReturn(Optional.of(new AxRenewalImplementationRun(
                UUID.randomUUID(),
                projectId,
                AxRenewalImplementationRun.STATUS_APPLIED,
                null,
                null,
                Instant.EPOCH,
                actor.id(),
                Instant.EPOCH,
                null,
                null,
                null)));
        when(destinations.find(projectId)).thenReturn(Optional.of(new AxRenewalDestination(
                projectId, AxRenewalDestination.KIND_FILESYSTEM_PATH, "/dest", null, null, Instant.EPOCH, actor.id())));
    }

    // -------------------------------------------------------------------
    // Error fix
    // -------------------------------------------------------------------

    @Test
    void error_fix_refuses_when_the_latest_run_locally_attempt_did_not_fail() {
        when(localRunner.status(projectId))
                .thenReturn(new AxRenewalLocalRunner.Status(AxRenewalLocalRunner.Status.STATE_RUNNING, List.of(), null, Instant.EPOCH));

        assertThatThrownBy(() -> service.startErrorFixImplementation(projectId, actor))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("no failed Run Locally attempt");
    }

    @Test
    void error_fix_refuses_without_a_real_applied_filesystem_destination() {
        when(localRunner.status(projectId))
                .thenReturn(new AxRenewalLocalRunner.Status(AxRenewalLocalRunner.Status.STATE_FAILED, List.of("boom"), null, Instant.EPOCH));
        when(implementations.findLatest(projectId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.startErrorFixImplementation(projectId, actor))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("no implementation has been applied");
    }

    @Test
    void error_fix_recognizes_a_real_render_error_even_while_run_locally_is_still_running() {
        // A page that throws for one request never exits the dev server
        // process (Next.js catches it and answers 500) -- status stays
        // running, never failed, so the bare status word is not enough here.
        List<String> realLog = List.of(
                "TypeError: Cannot read properties of undefined (reading 'role')", "GET / 500 in 4248ms");
        when(localRunner.status(projectId))
                .thenReturn(new AxRenewalLocalRunner.Status(AxRenewalLocalRunner.Status.STATE_RUNNING, realLog, "http://localhost:3000", Instant.EPOCH));
        appliedDestination();
        when(generator.canGenerate()).thenReturn(true);
        when(implementations.insert(any(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(inv -> new AxRenewalImplementationRun(
                        UUID.randomUUID(),
                        projectId,
                        inv.getArgument(3),
                        inv.getArgument(2),
                        null,
                        Instant.EPOCH,
                        actor.id(),
                        null,
                        null,
                        null,
                        inv.getArgument(4),
                        inv.getArgument(5),
                        inv.getArgument(6)));

        org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
        try {
            AxRenewalImplementationRun run = service.startErrorFixImplementation(projectId, actor);
            assertThat(run.status()).isEqualTo(AxRenewalImplementationRun.STATUS_GENERATING);
        } finally {
            org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void error_fix_refuses_when_no_local_model_is_configured() {
        when(localRunner.status(projectId))
                .thenReturn(new AxRenewalLocalRunner.Status(AxRenewalLocalRunner.Status.STATE_FAILED, List.of("boom"), null, Instant.EPOCH));
        appliedDestination();
        when(generator.canGenerate()).thenReturn(false);

        assertThatThrownBy(() -> service.startErrorFixImplementation(projectId, actor))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("no local model is configured");
    }

    @Test
    void error_fix_refuses_while_another_implementation_run_is_already_active() {
        when(localRunner.status(projectId))
                .thenReturn(new AxRenewalLocalRunner.Status(AxRenewalLocalRunner.Status.STATE_FAILED, List.of("boom"), null, Instant.EPOCH));
        appliedDestination();
        when(generator.canGenerate()).thenReturn(true);
        when(implementations.findActive(projectId)).thenReturn(Optional.of(new AxRenewalImplementationRun(
                UUID.randomUUID(),
                projectId,
                AxRenewalImplementationRun.STATUS_GENERATING,
                null,
                null,
                Instant.EPOCH,
                actor.id(),
                null,
                null,
                null,
                null)));

        assertThatThrownBy(() -> service.startErrorFixImplementation(projectId, actor))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("already running");
    }

    @Test
    void error_fix_begins_generation_from_the_real_captured_log_against_the_destination_itself() {
        List<String> realLog = List.of("npm error code EADDRINUSE");
        when(localRunner.status(projectId))
                .thenReturn(new AxRenewalLocalRunner.Status(AxRenewalLocalRunner.Status.STATE_FAILED, realLog, null, Instant.EPOCH));
        appliedDestination();
        when(generator.canGenerate()).thenReturn(true);
        when(implementations.insert(any(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(inv -> new AxRenewalImplementationRun(
                        UUID.randomUUID(),
                        projectId,
                        inv.getArgument(3),
                        inv.getArgument(2),
                        null,
                        Instant.EPOCH,
                        actor.id(),
                        null,
                        null,
                        null,
                        inv.getArgument(4),
                        inv.getArgument(5),
                        inv.getArgument(6)));

        TransactionSynchronizationManager.initSynchronization();
        try {
            AxRenewalImplementationRun run = service.startErrorFixImplementation(projectId, actor);

            assertThat(run.status()).isEqualTo(AxRenewalImplementationRun.STATUS_GENERATING);
            assertThat(run.runKind()).isEqualTo(AxRenewalImplementationRun.RUN_KIND_ERROR_FIX);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        verify(implementations)
                .insert(any(), any(), any(), any(), isNull(), eq(AxRenewalImplementationRun.RUN_KIND_ERROR_FIX), any());
    }

    // -------------------------------------------------------------------
    // UI change
    // -------------------------------------------------------------------

    @Test
    void ui_change_refuses_a_blank_description() {
        assertThatThrownBy(() -> service.startUiChangeImplementation(projectId, "   ", actor))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("describe the UI change");
        verify(implementations, never()).insert(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void ui_change_refuses_without_a_real_applied_filesystem_destination() {
        when(implementations.findLatest(projectId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.startUiChangeImplementation(projectId, "make the header purple", actor))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("no implementation has been applied");
    }

    @Test
    void ui_change_refuses_when_no_local_model_is_configured() {
        appliedDestination();
        when(generator.canGenerate()).thenReturn(false);

        assertThatThrownBy(() -> service.startUiChangeImplementation(projectId, "make the header purple", actor))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("no local model is configured");
    }

    @Test
    void ui_change_begins_generation_from_the_trimmed_description_against_the_destination_itself() {
        appliedDestination();
        when(generator.canGenerate()).thenReturn(true);
        when(implementations.insert(any(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(inv -> new AxRenewalImplementationRun(
                        UUID.randomUUID(),
                        projectId,
                        inv.getArgument(3),
                        inv.getArgument(2),
                        null,
                        Instant.EPOCH,
                        actor.id(),
                        null,
                        null,
                        null,
                        inv.getArgument(4),
                        inv.getArgument(5),
                        inv.getArgument(6)));

        TransactionSynchronizationManager.initSynchronization();
        try {
            AxRenewalImplementationRun run =
                    service.startUiChangeImplementation(projectId, "  make the header purple  ", actor);

            assertThat(run.status()).isEqualTo(AxRenewalImplementationRun.STATUS_GENERATING);
            assertThat(run.runKind()).isEqualTo(AxRenewalImplementationRun.RUN_KIND_UI_CHANGE);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        verify(implementations)
                .insert(
                        any(),
                        any(),
                        any(),
                        any(),
                        isNull(),
                        eq(AxRenewalImplementationRun.RUN_KIND_UI_CHANGE),
                        eq("make the header purple"));
    }
}
