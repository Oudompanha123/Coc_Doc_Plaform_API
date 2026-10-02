package com.coc.docs.axrenewal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.coc.docs.account.AppRole;
import com.coc.docs.audit.AuditRepository;
import com.coc.docs.auth.CurrentUser;
import com.coc.docs.auth.TestCallers;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The Build → Implementation run history (ADR-078).
 *
 * <p>A non-member's refusal is {@code AxRenewalMembershipTest}'s (it is on the
 * every-project-access list); the route floor is {@code SecurityRulesTest}'s;
 * the SQL itself was hand-run against PostgreSQL. This covers what the
 * service does with a member's request.
 *
 * <p>// req: ADR-078
 */
class AxRenewalRunHistoryTest {

    private AxRenewalImplementationRepository implementations;
    private AuditRepository audit;
    private AxRenewalService service;

    private final UUID projectId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        AxRenewalProjectRepository projects = mock(AxRenewalProjectRepository.class);
        implementations = mock(AxRenewalImplementationRepository.class);
        audit = mock(AuditRepository.class);
        when(projects.findById(projectId)).thenReturn(Optional.of(new AxRenewalProject(
                projectId,
                "Legacy Portal",
                null,
                AxRenewalProject.STATUS_COMPLETED,
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
                audit,
                new ObjectMapper(),
                mock(LocalClaudeAnalyzer.class),
                mock(LocalClaudeChat.class),
                new AxRenewalProperties(null, null),
                mock(AxRenewalCodeGenerator.class),
                mock(AxRenewalImplementationRunner.class),
                mock(AxRenewalLocalRunner.class),
                mock(AxRenewalTestRunner.class),
                event -> {});
    }

    private static AxRenewalRunSummary run(String kind, String status) {
        return new AxRenewalRunSummary(
                UUID.randomUUID(), kind, status, Instant.EPOCH, UUID.randomUUID(), "Dara", null, null, null, 0);
    }

    @Test
    void returns_the_project_s_runs_as_the_repository_orders_them() {
        // Newest first is the query's job (order by started_at desc); the
        // service must not reorder or drop anything.
        List<AxRenewalRunSummary> runs = List.of(
                run(AxRenewalImplementationRun.RUN_KIND_UI_CHANGE, "applied"),
                run(AxRenewalImplementationRun.RUN_KIND_ERROR_FIX, "rejected"),
                run(AxRenewalImplementationRun.RUN_KIND_PLAN, "failed"));
        when(implementations.listSummaries(projectId, AxRenewalService.RUN_HISTORY_LIMIT)).thenReturn(runs);

        assertThat(service.listRuns(projectId, TestCallers.user(Set.of(AppRole.REVIEWER))))
                .containsExactlyElementsOf(runs);
    }

    @Test
    void asks_for_at_most_the_history_limit() {
        when(implementations.listSummaries(any(), anyInt())).thenReturn(List.of());

        service.listRuns(projectId, TestCallers.user(AppRole.ADMIN));

        verify(implementations).listSummaries(projectId, 50);
    }

    @Test
    void is_a_plain_read_that_audits_nothing() {
        when(implementations.listSummaries(any(), anyInt())).thenReturn(List.of());

        service.listRuns(projectId, TestCallers.user(AppRole.ADMIN));

        verifyNoMoreInteractions(audit);
    }
}
