package com.coc.docs.axrenewal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
import org.springframework.http.HttpStatus;

/**
 * Renaming a project and editing its description (ADR-075).
 *
 * <p>Who may do it is covered elsewhere — the Admin route floor by {@code
 * SecurityRulesTest}, membership by {@code AxRenewalMembershipTest}. This
 * covers what a rename does.
 *
 * <p>// req: ADR-075
 */
class AxRenewalUpdateProjectTest {

    private AxRenewalProjectRepository projects;
    private AuditRepository audit;
    private AxRenewalService service;

    private final UUID projectId = UUID.randomUUID();
    private final CurrentUser admin = TestCallers.user(AppRole.ADMIN);

    @BeforeEach
    void setUp() {
        projects = mock(AxRenewalProjectRepository.class);
        audit = mock(AuditRepository.class);
        when(projects.findById(projectId)).thenReturn(Optional.of(project("Legacy Portl", "old notes")));
        when(projects.updateDetails(eq(projectId), any(), any()))
                .thenAnswer(inv -> project(inv.getArgument(1), inv.getArgument(2)));

        service = new AxRenewalService(
                projects,
                mock(AxRenewalAnalysisRepository.class),
                mock(AxRenewalPlanRepository.class),
                mock(AxRenewalDatabaseConnectionRepository.class),
                mock(AxRenewalDestinationRepository.class),
                mock(AxRenewalConfirmationRepository.class),
                mock(AxRenewalImplementationRepository.class),
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

    private AxRenewalProject project(String name, String description) {
        return new AxRenewalProject(
                projectId,
                name,
                description,
                AxRenewalProject.STATUS_PLANNING,
                null,
                null,
                null,
                null,
                null,
                admin.id(),
                Instant.EPOCH,
                Instant.EPOCH,
                3);
    }

    @Test
    void renames_and_re_describes_trimming_both() {
        AxRenewalProject updated = service.updateDetails(projectId, "  Legacy Portal ", "  new notes  ", admin);

        verify(projects).updateDetails(projectId, "Legacy Portal", "new notes");
        assertThat(updated.name()).isEqualTo("Legacy Portal");
    }

    @Test
    void audits_the_name_before_and_after_so_the_trail_recognises_either() {
        service.updateDetails(projectId, "Legacy Portal", "old notes", admin);

        ArgumentCaptor<JsonNode> detail = ArgumentCaptor.forClass(JsonNode.class);
        verify(audit)
                .append(
                        eq(admin.id()),
                        eq(admin.email()),
                        eq(AuditAction.AX_RENEWAL_PROJECT_UPDATE),
                        eq(AuditAction.TARGET_AX_RENEWAL_PROJECT),
                        eq(projectId),
                        detail.capture());
        assertThat(detail.getValue().path("previousName").asText()).isEqualTo("Legacy Portl");
        assertThat(detail.getValue().path("name").asText()).isEqualTo("Legacy Portal");
        assertThat(detail.getValue().path("descriptionChanged").asBoolean()).isFalse();
    }

    @Test
    void a_blank_description_clears_it() {
        service.updateDetails(projectId, "Legacy Portl", "   ", admin);

        verify(projects).updateDetails(projectId, "Legacy Portl", null);
    }

    @Test
    void a_blank_name_is_refused_and_nothing_changes() {
        assertThatThrownBy(() -> service.updateDetails(projectId, "   ", "x", admin))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST));

        verify(projects, never()).updateDetails(any(), any(), any());
        verifyNoInteractions(audit);
    }

    @Test
    void saving_it_unchanged_writes_and_audits_nothing() {
        // Re-saving the form untouched is not an event worth a row in the trail.
        AxRenewalProject result = service.updateDetails(projectId, "Legacy Portl", "old notes", admin);

        assertThat(result.name()).isEqualTo("Legacy Portl");
        verify(projects, never()).updateDetails(any(), any(), any());
        verifyNoInteractions(audit);
    }

    @Test
    void does_not_touch_the_plan_revision() {
        // A name is not plan content: bumping the revision would refuse a
        // teammate's plan save as "changed by someone else" (ADR-067) for a
        // change they could not have overwritten.
        service.updateDetails(projectId, "Legacy Portal", null, admin);

        verify(projects, never()).lockRevision(any());
        verify(projects, never()).incrementRevision(any());
    }
}
