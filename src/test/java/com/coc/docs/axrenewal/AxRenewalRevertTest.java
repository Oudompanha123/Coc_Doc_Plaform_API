package com.coc.docs.axrenewal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
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
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Reverting one specific applied run (ADR-060 §revert) — restores every file
 * it touched to its own recorded {@code beforeContent} (a created file is
 * deleted instead), but only while it is still the current latest applied
 * run: an older applied run may have had its own files touched again since
 * by something newer, and reverting past that would silently discard that
 * newer, already-approved change.
 */
class AxRenewalRevertTest {

    private AxRenewalProjectRepository projects;
    private AxRenewalDestinationRepository destinations;
    private AxRenewalImplementationRepository implementations;
    private AxRenewalService service;

    private final UUID projectId = UUID.randomUUID();
    private final UUID runId = UUID.randomUUID();
    private final CurrentUser actor = TestCallers.user(AppRole.SUPER_ADMIN);

    @BeforeEach
    void setUp() {
        projects = mock(AxRenewalProjectRepository.class);
        destinations = mock(AxRenewalDestinationRepository.class);
        implementations = mock(AxRenewalImplementationRepository.class);

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
                new AxRenewalProperties(null, null),
                mock(AxRenewalCodeGenerator.class),
                mock(AxRenewalImplementationRunner.class),
                mock(AxRenewalLocalRunner.class),
                mock(AxRenewalTestRunner.class));
    }

    private AxRenewalImplementationRun appliedRun(UUID id) {
        return new AxRenewalImplementationRun(
                id, projectId, AxRenewalImplementationRun.STATUS_APPLIED, null, null, Instant.EPOCH, actor.id(),
                Instant.EPOCH, null, null, null);
    }

    @Test
    void refuses_a_run_that_was_never_applied() {
        when(implementations.findById(runId)).thenReturn(Optional.of(new AxRenewalImplementationRun(
                runId, projectId, AxRenewalImplementationRun.STATUS_REJECTED, null, null, Instant.EPOCH, actor.id(),
                Instant.EPOCH, null, null, null)));

        assertThatThrownBy(() -> service.revertImplementation(projectId, runId, actor))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("nothing to revert");
    }

    @Test
    void refuses_a_run_that_is_no_longer_the_latest_applied_run() {
        when(implementations.findById(runId)).thenReturn(Optional.of(appliedRun(runId)));
        when(implementations.findLatest(projectId)).thenReturn(Optional.of(appliedRun(UUID.randomUUID())));

        assertThatThrownBy(() -> service.revertImplementation(projectId, runId, actor))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("only the most recently applied run");
    }

    @Test
    void restores_a_modified_file_to_its_real_prior_content_and_deletes_a_created_one(@TempDir Path destinationRoot)
            throws IOException {
        when(implementations.findById(runId)).thenReturn(Optional.of(appliedRun(runId)));
        when(implementations.findLatest(projectId)).thenReturn(Optional.of(appliedRun(runId)));
        when(destinations.find(projectId)).thenReturn(Optional.of(new AxRenewalDestination(
                projectId, AxRenewalDestination.KIND_FILESYSTEM_PATH, destinationRoot.toString(), null, null,
                Instant.EPOCH, actor.id())));

        Files.writeString(destinationRoot.resolve("app.css"), "color: purple;");
        Files.writeString(destinationRoot.resolve("new-widget.css"), "border: 1px;");
        when(implementations.findGeneratedChanges(runId)).thenReturn(List.of(
                new AxRenewalGeneratedChange(
                        UUID.randomUUID(), runId, "app.css", AxRenewalGeneratedChange.KIND_MODIFY,
                        "color: blue;", "color: purple;", 0),
                new AxRenewalGeneratedChange(
                        UUID.randomUUID(), runId, "new-widget.css", AxRenewalGeneratedChange.KIND_CREATE,
                        null, "border: 1px;", 1)));

        AxRenewalImplementationRun result = service.revertImplementation(projectId, runId, actor);

        assertThat(Files.readString(destinationRoot.resolve("app.css"))).isEqualTo("color: blue;");
        assertThat(Files.exists(destinationRoot.resolve("new-widget.css"))).isFalse();
        assertThat(result).isNotNull();
        org.mockito.Mockito.verify(implementations).markReverted(runId);
    }

    @Test
    void recreates_a_file_this_run_had_deleted(@TempDir Path destinationRoot) throws IOException {
        when(implementations.findById(runId)).thenReturn(Optional.of(appliedRun(runId)));
        when(implementations.findLatest(projectId)).thenReturn(Optional.of(appliedRun(runId)));
        when(destinations.find(projectId)).thenReturn(Optional.of(new AxRenewalDestination(
                projectId, AxRenewalDestination.KIND_FILESYSTEM_PATH, destinationRoot.toString(), null, null,
                Instant.EPOCH, actor.id())));
        when(implementations.findGeneratedChanges(runId)).thenReturn(List.of(new AxRenewalGeneratedChange(
                UUID.randomUUID(), runId, "old-page.css", AxRenewalGeneratedChange.KIND_DELETE,
                "body { color: red; }", null, 0)));

        service.revertImplementation(projectId, runId, actor);

        assertThat(Files.readString(destinationRoot.resolve("old-page.css"))).isEqualTo("body { color: red; }");
    }

    @Test
    void refuses_a_non_filesystem_destination() {
        when(implementations.findById(runId)).thenReturn(Optional.of(appliedRun(runId)));
        when(implementations.findLatest(projectId)).thenReturn(Optional.of(appliedRun(runId)));
        when(destinations.find(projectId)).thenReturn(Optional.of(new AxRenewalDestination(
                projectId, AxRenewalDestination.KIND_GIT_REPO, "git@example.com:x/y.git", null, null, Instant.EPOCH,
                actor.id())));

        assertThatThrownBy(() -> service.revertImplementation(projectId, runId, actor))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("filesystem destination");
    }
}
