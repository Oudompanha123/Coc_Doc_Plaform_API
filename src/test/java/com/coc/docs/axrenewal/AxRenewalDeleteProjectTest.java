package com.coc.docs.axrenewal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.coc.docs.account.AppRole;
import com.coc.docs.audit.AuditAction;
import com.coc.docs.audit.AuditRepository;
import com.coc.docs.auth.CurrentUser;
import com.coc.docs.auth.TestCallers;
import com.coc.docs.web.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * {@code AxRenewalService#delete}'s own preconditions (ADR-050): the project
 * must exist, and nothing this project's own background work is doing may
 * still be in flight — the same "one active thing" shape {@link
 * AxRenewalService#startImplementation} already uses, checked here rather
 * than in {@code SecurityConfig} because it depends on row/process state,
 * not on who is calling.
 */
class AxRenewalDeleteProjectTest {

    private AxRenewalProjectRepository projects;
    private AxRenewalImplementationRepository implementations;
    private AxRenewalLocalRunner localRunner;
    private AuditRepository audit;
    private AxRenewalService service;

    private final UUID projectId = UUID.randomUUID();
    private final CurrentUser actor = TestCallers.user(AppRole.ADMIN);

    @BeforeEach
    void setUp() {
        projects = mock(AxRenewalProjectRepository.class);
        implementations = mock(AxRenewalImplementationRepository.class);
        localRunner = mock(AxRenewalLocalRunner.class);
        audit = mock(AuditRepository.class);

        when(projects.findById(projectId)).thenReturn(Optional.of(new AxRenewalProject(
                projectId,
                "Legacy Portal",
                null,
                AxRenewalProject.STATUS_DRAFT,
                null,
                null,
                null,
                null,
                null,
                null,
                Instant.EPOCH,
                Instant.EPOCH)));
        when(implementations.findActive(projectId)).thenReturn(Optional.empty());
        when(localRunner.isRunning(projectId)).thenReturn(false);

        service = new AxRenewalService(
                projects,
                mock(AxRenewalAnalysisRepository.class),
                mock(AxRenewalPlanRepository.class),
                mock(AxRenewalDatabaseConnectionRepository.class),
                mock(AxRenewalDestinationRepository.class),
                mock(AxRenewalConfirmationRepository.class),
                implementations,
                mock(AxRenewalCommentRepository.class),
                audit,
                new ObjectMapper(),
                mock(LocalClaudeAnalyzer.class),
                mock(LocalClaudeChat.class),
                new AxRenewalProperties(null, null),
                mock(AxRenewalCodeGenerator.class),
                mock(AxRenewalImplementationRunner.class),
                localRunner);
    }

    @Test
    void deletes_the_row_and_audits_its_name_first() {
        service.delete(projectId, actor);

        verify(projects).delete(projectId);

        ArgumentCaptor<JsonNode> detail = ArgumentCaptor.forClass(JsonNode.class);
        verify(audit)
                .append(
                        eq(actor.id()),
                        eq(actor.email()),
                        eq(AuditAction.AX_RENEWAL_PROJECT_DELETE),
                        eq(AuditAction.TARGET_AX_RENEWAL_PROJECT),
                        eq(projectId),
                        detail.capture());
        assertThat(detail.getValue().path("name").asText()).isEqualTo("Legacy Portal");
    }

    @Test
    void a_missing_project_is_a_404_not_a_silent_no_op() {
        when(projects.findById(projectId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(projectId, actor)).isInstanceOf(ApiException.class);

        verify(projects, never()).delete(any());
    }

    @Test
    void refuses_while_an_implementation_run_is_still_active() {
        when(implementations.findActive(projectId)).thenReturn(Optional.of(mock(AxRenewalImplementationRun.class)));

        assertThatThrownBy(() -> service.delete(projectId, actor))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("active implementation run");

        verify(projects, never()).delete(any());
        verify(audit, never()).append(any(), any(), any(), any(), any(), any());
    }

    @Test
    void refuses_while_a_local_run_is_still_live() {
        when(localRunner.isRunning(projectId)).thenReturn(true);

        assertThatThrownBy(() -> service.delete(projectId, actor))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("local run");

        verify(projects, never()).delete(any());
        verify(audit, never()).append(any(), any(), any(), any(), any(), any());
    }
}
