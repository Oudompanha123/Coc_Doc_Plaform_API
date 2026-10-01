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
import com.coc.docs.audit.AuditRepository;
import com.coc.docs.auth.CurrentUser;
import com.coc.docs.auth.TestCallers;
import com.coc.docs.web.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A caller-supplied source path override for Run analysis (ADR-054) — the
 * validation half of the feature, which is why it lives in {@code
 * AxRenewalService} and not {@code LocalClaudeAnalyzer} (see that class's
 * own Javadoc on the two-arg {@code analyse} overload): a path a person just
 * typed deserves an immediate 400, not the silent keyword-stub fallback a
 * stale <em>configured</em> path degrades to.
 *
 * <p>// req: ADR-037, ADR-043, ADR-054
 */
class AxRenewalRunAnalysisSourcePathTest {

    private AxRenewalProjectRepository projects;
    private AxRenewalAnalysisRepository analyses;
    private LocalClaudeAnalyzer localClaude;
    private AxRenewalService service;

    private final UUID projectId = UUID.randomUUID();
    private final CurrentUser actor = TestCallers.user(AppRole.ADMIN);

    @BeforeEach
    void setUp() {
        projects = mock(AxRenewalProjectRepository.class);
        analyses = mock(AxRenewalAnalysisRepository.class);
        localClaude = mock(LocalClaudeAnalyzer.class);

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
                Instant.EPOCH,
                0)));
        when(localClaude.analyse(any(), any())).thenReturn(Optional.empty());
        when(analyses.find(projectId)).thenReturn(Optional.of(mock(AxRenewalAnalysis.class)));

        service = new AxRenewalService(
                projects,
                analyses,
                mock(AxRenewalPlanRepository.class),
                mock(AxRenewalDatabaseConnectionRepository.class),
                mock(AxRenewalDestinationRepository.class),
                mock(AxRenewalConfirmationRepository.class),
                mock(AxRenewalImplementationRepository.class),
                mock(AxRenewalCommentRepository.class),
                TestMembers.everyone(),
                mock(com.coc.docs.account.AccountRepository.class),
                mock(AuditRepository.class),
                new ObjectMapper(),
                localClaude,
                mock(LocalClaudeChat.class),
                new AxRenewalProperties(null, null),
                mock(AxRenewalCodeGenerator.class),
                mock(AxRenewalImplementationRunner.class),
                mock(AxRenewalLocalRunner.class),
                mock(AxRenewalTestRunner.class),
                event -> {});
    }

    @Test
    void a_blank_override_is_not_validated_and_reaches_the_analyzer_as_null() {
        service.runAnalysis(projectId, true, "   ", actor);

        verify(localClaude).analyse(any(), eq(null));
    }

    @Test
    void no_override_at_all_reaches_the_analyzer_as_null() {
        service.runAnalysis(projectId, true, null, actor);

        verify(localClaude).analyse(any(), eq(null));
    }

    @Test
    void an_override_that_is_not_a_real_directory_is_refused_before_the_analyzer_ever_runs(@TempDir Path tmp) {
        String notADirectory = tmp.resolve("does-not-exist").toString();

        assertThatThrownBy(() -> service.runAnalysis(projectId, true, notADirectory, actor))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("isn't a folder this backend can read");

        verify(localClaude, never()).analyse(any(), any());
    }

    @Test
    void a_real_directory_override_is_passed_through_to_the_analyzer(@TempDir Path tmp) {
        service.runAnalysis(projectId, true, tmp.toString(), actor);

        verify(localClaude).analyse(any(), eq(tmp.toString()));
    }

    @Test
    void the_override_is_ignored_entirely_when_useModel_is_false() {
        // Disconnect (ADR-043) means the keyword stub runs regardless of
        // what path was typed in -- the analyzer must not even be asked.
        service.runAnalysis(projectId, false, "not even validated", actor);

        verify(localClaude, never()).analyse(any(), any());
    }
}
