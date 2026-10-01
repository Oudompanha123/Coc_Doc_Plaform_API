package com.coc.docs.axrenewal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.coc.docs.account.AppRole;
import com.coc.docs.audit.AuditRepository;
import com.coc.docs.auth.CurrentUser;
import com.coc.docs.auth.TestCallers;
import com.coc.docs.web.ApiDtos;
import com.coc.docs.web.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * Reading a generating run's live progress (ADR-082). A non-member's refusal
 * is {@code AxRenewalMembershipTest}'s; the route floor is {@code
 * SecurityRulesTest}'s.
 *
 * <p>// req: ADR-082
 */
class AxRenewalImplementationProgressTest {

    private AxRenewalImplementationRepository implementations;
    private AxRenewalImplementationRunner runner;
    private AxRenewalService service;

    private final UUID projectId = UUID.randomUUID();
    private final UUID runId = UUID.randomUUID();
    private final CurrentUser reviewer = TestCallers.user(AppRole.REVIEWER);

    private final AxRenewalGenerationProgress.Snapshot working = new AxRenewalGenerationProgress.Snapshot(
            AxRenewalGenerationProgress.STEP_WORKING,
            Instant.parse("2026-10-01T09:01:00Z"),
            4,
            2,
            9,
            List.of(new AxRenewalGenerationProgress.Activity(
                    Instant.parse("2026-10-01T09:02:00Z"), "edit", "Editing src/App.tsx", "src/App.tsx")),
            false);

    @BeforeEach
    void setUp() {
        AxRenewalProjectRepository projects = mock(AxRenewalProjectRepository.class);
        implementations = mock(AxRenewalImplementationRepository.class);
        runner = mock(AxRenewalImplementationRunner.class);
        when(projects.findById(projectId)).thenReturn(Optional.of(new AxRenewalProject(
                projectId,
                "Legacy Portal",
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

    private void runBelongsTo(UUID project) {
        when(implementations.findById(runId)).thenReturn(Optional.of(new AxRenewalImplementationRun(
                runId,
                project,
                AxRenewalImplementationRun.STATUS_GENERATING,
                null,
                null,
                Instant.EPOCH,
                UUID.randomUUID(),
                null,
                null,
                null,
                null)));
    }

    @Test
    void a_member_reads_what_the_run_is_doing() {
        runBelongsTo(projectId);
        when(runner.progress(runId)).thenReturn(Optional.of(working));

        assertThat(service.implementationProgress(projectId, runId, reviewer)).contains(working);
    }

    @Test
    void empty_when_this_backend_is_not_generating_it() {
        runBelongsTo(projectId);
        when(runner.progress(runId)).thenReturn(Optional.empty());

        assertThat(service.implementationProgress(projectId, runId, reviewer)).isEmpty();
    }

    @Test
    void another_project_s_run_is_a_404_and_its_progress_is_never_read() {
        runBelongsTo(UUID.randomUUID());

        assertThatThrownBy(() -> service.implementationProgress(projectId, runId, reviewer))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.NOT_FOUND));
        verify(runner, never()).progress(runId);
    }

    @Test
    void the_response_carries_the_snapshot_as_it_is() {
        ApiDtos.AxRenewalGenerationProgressResponse response =
                ApiDtos.AxRenewalGenerationProgressResponse.of(Optional.of(working));

        assertThat(response.available()).isTrue();
        assertThat(response.step()).isEqualTo("working");
        assertThat(response.filesRead()).isEqualTo(4);
        assertThat(response.filesChanged()).isEqualTo(2);
        assertThat(response.actions()).isEqualTo(9);
        assertThat(response.activity()).singleElement().satisfies(a -> {
            assertThat(a.kind()).isEqualTo("edit");
            assertThat(a.text()).isEqualTo("Editing src/App.tsx");
            assertThat(a.path()).isEqualTo("src/App.tsx");
        });
    }

    @Test
    void unavailable_progress_is_empty_never_a_stand_in() {
        ApiDtos.AxRenewalGenerationProgressResponse response =
                ApiDtos.AxRenewalGenerationProgressResponse.of(Optional.empty());

        assertThat(response.available()).isFalse();
        assertThat(response.step()).isNull();
        assertThat(response.stepStartedAt()).isNull();
        assertThat(response.actions()).isZero();
        assertThat(response.activity()).isEmpty();
    }
}
