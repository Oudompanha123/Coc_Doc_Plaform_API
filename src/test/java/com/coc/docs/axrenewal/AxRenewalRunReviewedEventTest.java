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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * When approve and reject announce a review outcome (ADR-081): only once the
 * run is actually {@code applied} or {@code rejected}. Who is then told is
 * {@code AxRenewalRunReviewedNotifierTest}'s subject.
 *
 * <p>// req: ADR-081
 */
class AxRenewalRunReviewedEventTest {

    private AxRenewalDestinationRepository destinations;
    private AxRenewalImplementationRepository implementations;
    private AxRenewalService service;
    private final List<Object> published = new ArrayList<>();

    private final UUID projectId = UUID.randomUUID();
    private final UUID runId = UUID.randomUUID();
    private final UUID starter = UUID.randomUUID();
    private final CurrentUser reviewer = TestCallers.user(AppRole.SUPER_ADMIN);

    @TempDir
    Path destinationRoot;

    @TempDir
    Path staging;

    @BeforeEach
    void setUp() {
        AxRenewalProjectRepository projects = mock(AxRenewalProjectRepository.class);
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
                Instant.EPOCH,
                0)));
        when(destinations.find(projectId)).thenReturn(Optional.of(new AxRenewalDestination(
                projectId,
                AxRenewalDestination.KIND_FILESYSTEM_PATH,
                destinationRoot.toString(),
                null,
                null,
                Instant.EPOCH,
                reviewer.id())));
        when(implementations.findLatest(projectId)).thenReturn(Optional.of(run(AxRenewalImplementationRun.STATUS_APPLIED)));

        service = new AxRenewalService(
                projects,
                mock(AxRenewalAnalysisRepository.class),
                mock(AxRenewalPlanRepository.class),
                mock(AxRenewalDatabaseConnectionRepository.class),
                destinations,
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
                mock(AxRenewalTestRunner.class),
                published::add);
    }

    private AxRenewalImplementationRun run(String status) {
        return new AxRenewalImplementationRun(
                runId, projectId, status, null, null, Instant.EPOCH, starter, null, staging.toString(), null, null);
    }

    private void awaitingReviewWith(String path) {
        when(implementations.findById(runId)).thenReturn(Optional.of(run(AxRenewalImplementationRun.STATUS_AWAITING_REVIEW)));
        when(implementations.findGeneratedChanges(runId)).thenReturn(List.of(new AxRenewalGeneratedChange(
                UUID.randomUUID(), runId, path, AxRenewalGeneratedChange.KIND_CREATE, null, "content", 0)));
    }

    @Test
    void an_applied_approve_announces_it_was_approved_naming_starter_and_reviewer() {
        awaitingReviewWith("src/App.tsx");

        service.approveImplementation(projectId, runId, reviewer);

        assertThat(Files.exists(destinationRoot.resolve("src/App.tsx"))).isTrue();
        assertThat(published).containsExactly(new AxRenewalRunReviewedEvent(projectId, runId, starter, reviewer.id(), true));
    }

    @Test
    void a_reject_announces_it_was_rejected() {
        awaitingReviewWith("src/App.tsx");

        service.rejectImplementation(projectId, runId, reviewer);

        assertThat(published).containsExactly(new AxRenewalRunReviewedEvent(projectId, runId, starter, reviewer.id(), false));
    }

    @Test
    void an_approve_that_fails_to_apply_announces_nothing() {
        // A generated path that would escape the destination is refused mid-apply.
        awaitingReviewWith("../outside.txt");

        assertThatThrownBy(() -> service.approveImplementation(projectId, runId, reviewer))
                .isInstanceOf(ApiException.class);

        assertThat(published).isEmpty();
    }

    @Test
    void a_run_not_awaiting_review_is_refused_and_announces_nothing() {
        when(implementations.findById(runId)).thenReturn(Optional.of(run(AxRenewalImplementationRun.STATUS_APPLIED)));

        assertThatThrownBy(() -> service.approveImplementation(projectId, runId, reviewer))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.rejectImplementation(projectId, runId, reviewer))
                .isInstanceOf(ApiException.class);

        assertThat(published).isEmpty();
    }
}
