package com.coc.docs.axrenewal;

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
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The real preconditions Run Locally must check before ever spawning
 * anything (ADR-048): the latest implementation run actually reached
 * {@code applied}, the destination is a real filesystem path, and its own
 * {@code package.json} really has a {@code dev} script — never assumed.
 */
class AxRenewalRunLocallyTest {

    private AxRenewalProjectRepository projects;
    private AxRenewalImplementationRepository implementations;
    private AxRenewalDestinationRepository destinations;
    private AxRenewalLocalRunner localRunner;
    private AxRenewalService service;

    private final UUID projectId = UUID.randomUUID();
    private final CurrentUser actor = TestCallers.user(AppRole.SUPER_ADMIN);

    @BeforeEach
    void setUp() {
        projects = mock(AxRenewalProjectRepository.class);
        implementations = mock(AxRenewalImplementationRepository.class);
        destinations = mock(AxRenewalDestinationRepository.class);
        localRunner = mock(AxRenewalLocalRunner.class);

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
                destinations,
                mock(AxRenewalConfirmationRepository.class),
                implementations,
                mock(AxRenewalCommentRepository.class),
                mock(AuditRepository.class),
                new ObjectMapper(),
                mock(LocalClaudeAnalyzer.class),
                mock(LocalClaudeChat.class),
                new AxRenewalProperties(null, null, null),
                mock(AxRenewalCodeGenerator.class),
                mock(AxRenewalImplementationRunner.class),
                localRunner);
    }

    private void appliedRun(Path destinationRoot) {
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
                null)));
        when(destinations.find(projectId)).thenReturn(Optional.of(new AxRenewalDestination(
                projectId,
                AxRenewalDestination.KIND_FILESYSTEM_PATH,
                destinationRoot.toString(),
                null,
                null,
                Instant.EPOCH,
                actor.id())));
    }

    @Test
    void refuses_when_no_implementation_run_exists_yet() throws IOException {
        when(implementations.findLatest(projectId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.startRunLocally(projectId, actor))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("no implementation has been applied");

        verify(localRunner, never()).start(any(), any(Path.class), any(String.class));
    }

    @Test
    void refuses_when_the_latest_run_has_not_reached_applied(@TempDir Path destinationRoot) throws IOException {
        when(implementations.findLatest(projectId)).thenReturn(Optional.of(new AxRenewalImplementationRun(
                UUID.randomUUID(),
                projectId,
                AxRenewalImplementationRun.STATUS_AWAITING_REVIEW,
                null,
                null,
                Instant.EPOCH,
                actor.id(),
                null,
                null,
                null)));

        assertThatThrownBy(() -> service.startRunLocally(projectId, actor))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("has not been applied yet");

        verify(localRunner, never()).start(any(), any(Path.class), any(String.class));
    }

    @Test
    void refuses_a_non_filesystem_destination_even_once_applied() throws IOException {
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
                null)));
        when(destinations.find(projectId)).thenReturn(Optional.of(new AxRenewalDestination(
                projectId, AxRenewalDestination.KIND_GIT_REPO, "git@example.com:x/y.git", null, null, Instant.EPOCH, actor.id())));

        assertThatThrownBy(() -> service.startRunLocally(projectId, actor))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("filesystem destination");

        verify(localRunner, never()).start(any(), any(Path.class), any(String.class));
    }

    @Test
    void refuses_when_the_destination_has_no_package_json(@TempDir Path destinationRoot) throws IOException {
        appliedRun(destinationRoot);

        assertThatThrownBy(() -> service.startRunLocally(projectId, actor))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("no package.json");

        verify(localRunner, never()).start(any(), any(Path.class), any(String.class));
    }

    @Test
    void refuses_when_package_json_has_no_dev_script(@TempDir Path destinationRoot) throws IOException {
        appliedRun(destinationRoot);
        Files.writeString(destinationRoot.resolve("package.json"), "{\"scripts\": {\"build\": \"next build\"}}");

        assertThatThrownBy(() -> service.startRunLocally(projectId, actor))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("no \"dev\" script");

        verify(localRunner, never()).start(any(), any(Path.class), any(String.class));
    }

    @Test
    void starts_the_runner_once_every_real_precondition_is_met(@TempDir Path destinationRoot) throws IOException {
        appliedRun(destinationRoot);
        Files.writeString(destinationRoot.resolve("package.json"), "{\"scripts\": {\"dev\": \"next dev\"}}");
        when(localRunner.status(projectId))
                .thenReturn(new AxRenewalLocalRunner.Status(AxRenewalLocalRunner.Status.STATE_STARTING, java.util.List.of(), null, Instant.EPOCH));

        service.startRunLocally(projectId, actor);

        verify(localRunner).start(projectId, destinationRoot.toAbsolutePath().normalize(), "dev");
    }

    @Test
    void stop_always_reaches_the_runner_even_with_no_applied_run() {
        when(localRunner.status(projectId))
                .thenReturn(new AxRenewalLocalRunner.Status(AxRenewalLocalRunner.Status.STATE_STOPPED, java.util.List.of(), null, null));

        service.stopRunLocally(projectId, actor);

        verify(localRunner).stop(projectId);
    }
}
