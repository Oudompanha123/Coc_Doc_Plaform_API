package com.coc.docs.axrenewal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.coc.docs.account.AppRole;
import com.coc.docs.audit.AuditRepository;
import com.coc.docs.auth.CurrentUser;
import com.coc.docs.auth.TestCallers;
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
 * Approve must write from the real content this run already captured in the
 * database, not re-read the disposable staging copy off disk — that copy is
 * a plain OS temp directory with no retention guarantee, and by the time
 * somebody gets around to approving (a different machine than the one
 * Generate ran on, or the OS's own temp-file cleanup), it may simply no
 * longer be there even though the run is genuinely awaiting review with real
 * content already recorded.
 */
class AxRenewalApproveImplementationTest {

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

    @Test
    void writes_the_recorded_content_even_when_the_staging_copy_no_longer_exists(@TempDir Path destinationRoot)
            throws IOException {
        // Never created -- exactly the real-world case: a staging directory
        // whose path is still recorded on the run but that no longer exists
        // wherever Approve is now running.
        Path vanishedStaging = destinationRoot.resolve("no-longer-there");

        when(implementations.findById(runId)).thenReturn(Optional.of(new AxRenewalImplementationRun(
                runId,
                projectId,
                AxRenewalImplementationRun.STATUS_AWAITING_REVIEW,
                null,
                null,
                Instant.EPOCH,
                actor.id(),
                null,
                vanishedStaging.toString(),
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
        when(implementations.findGeneratedChanges(runId)).thenReturn(List.of(new AxRenewalGeneratedChange(
                UUID.randomUUID(),
                runId,
                ".eslintrc.json",
                AxRenewalGeneratedChange.KIND_CREATE,
                null,
                "{\"extends\": \"next/core-web-vitals\"}",
                0)));
        when(implementations.findLatest(projectId)).thenReturn(Optional.of(new AxRenewalImplementationRun(
                runId,
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

        service.approveImplementation(projectId, runId, actor);

        assertThat(Files.readString(destinationRoot.resolve(".eslintrc.json")))
                .isEqualTo("{\"extends\": \"next/core-web-vitals\"}");
    }
}
