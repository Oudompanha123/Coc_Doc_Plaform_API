package com.coc.docs.axrenewal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.InOrder;
import org.springframework.http.HttpStatus;

/**
 * Stale-edit protection on a shared AX Renewal plan (ADR-061).
 *
 * <p>Two people with the same section open: the second save must be refused,
 * not silently erase the first. Tested at the service rather than through
 * MockMvc because the rule is "compare against the row's current revision",
 * which is row data — {@code SecurityRulesTest} covers only that a request
 * without a revision never reaches this far.
 *
 * <p>// req: ADR-061, FR-CONT-013
 */
class AxRenewalStaleEditTest {

    private static final int CURRENT = 4;
    private static final int STALE = 3;

    private AxRenewalProjectRepository projects;
    private AxRenewalPlanRepository plan;
    private AxRenewalDatabaseConnectionRepository dbConnections;
    private AxRenewalDestinationRepository destinations;
    private AxRenewalConfirmationRepository confirmations;
    private AuditRepository audit;
    private AxRenewalService service;

    private final UUID projectId = UUID.randomUUID();
    private final CurrentUser admin = TestCallers.user(AppRole.ADMIN);
    private final CurrentUser developer = TestCallers.user(java.util.Set.of(AppRole.DEVELOPER));

    @BeforeEach
    void setUp() {
        projects = mock(AxRenewalProjectRepository.class);
        plan = mock(AxRenewalPlanRepository.class);
        dbConnections = mock(AxRenewalDatabaseConnectionRepository.class);
        destinations = mock(AxRenewalDestinationRepository.class);
        confirmations = mock(AxRenewalConfirmationRepository.class);
        audit = mock(AuditRepository.class);

        // findById for the membership check (ADR-063), which runs first;
        // lockRevision for the revision check this class is about.
        when(projects.findById(projectId)).thenReturn(Optional.of(project()));
        when(projects.lockRevision(projectId)).thenReturn(Optional.of(CURRENT));
        when(projects.incrementRevision(projectId)).thenReturn(CURRENT + 1);

        service = new AxRenewalService(
                projects,
                mock(AxRenewalAnalysisRepository.class),
                plan,
                dbConnections,
                destinations,
                confirmations,
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
                mock(AxRenewalTestRunner.class));
    }

    /** Every plan edit, called with the revision given — one row per endpoint that must refuse a stale write. */
    static Stream<Arguments> everyPlanEdit() {
        return Stream.of(
                edit("stack move", (s, r) -> s.service.updateStackMove(s.projectId, List.of(), r, s.admin)),
                edit("architecture", (s, r) -> s.service.updateArchitecture(s.projectId, "x", null, r, s.admin)),
                edit("pages", (s, r) -> s.service.updatePages(s.projectId, List.of(), r, s.admin)),
                edit("database plan", (s, r) -> s.service.updateDatabasePlan(s.projectId, List.of(), r, s.admin)),
                edit("database connect", (s, r) -> s.service.connectDatabase(
                        s.projectId, "postgres", "db.local", 5432, "legacy", "public", r, s.admin)),
                edit("destination", (s, r) -> s.service.setDestination(
                        s.projectId, "git_repo", "git@example.com:renewed.git", "main", null, r, s.admin)));
    }

    @FunctionalInterface
    interface PlanEdit {
        void apply(AxRenewalStaleEditTest self, int revision);
    }

    private static Arguments edit(String name, PlanEdit call) {
        return Arguments.of(name, call);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("everyPlanEdit")
    void a_stale_revision_is_refused_with_a_409_and_writes_nothing(String name, PlanEdit call) {
        assertThatThrownBy(() -> call.apply(this, STALE))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                    // Both numbers, so the person can tell "someone saved
                    // since I opened it" from a bug in their own client.
                    assertThat(e.getMessage())
                            .contains("changed by someone else")
                            .contains("revision " + STALE)
                            .contains("revision " + CURRENT)
                            .contains("Reload");
                });

        verify(projects, never()).incrementRevision(any());
        verifyNoInteractions(plan, dbConnections, destinations, confirmations, audit);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("everyPlanEdit")
    void a_missing_project_is_a_404_not_a_conflict(String name, PlanEdit call) {
        when(projects.findById(projectId)).thenReturn(Optional.empty());
        when(projects.lockRevision(projectId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> call.apply(this, CURRENT))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.NOT_FOUND));

        verify(projects, never()).incrementRevision(any());
    }

    @Test
    void a_current_revision_locks_the_row_bumps_it_then_writes() {
        when(plan.findStackLayers(projectId)).thenReturn(List.of());

        service.updateStackMove(projectId, List.of(), CURRENT, admin);

        // Lock and bump before the write: the row lock is what keeps a
        // concurrent edit from interleaving between the check and the write.
        InOrder order = inOrder(projects, plan);
        order.verify(projects).lockRevision(projectId);
        order.verify(projects).incrementRevision(projectId);
        order.verify(plan).replaceStackLayers(eq(projectId), any(), eq(admin.id()));
    }

    @Test
    void each_plan_edit_bumps_the_revision_exactly_once() {
        when(plan.findPageMappings(projectId)).thenReturn(List.of());

        service.updatePages(projectId, List.of(), CURRENT, admin);

        verify(projects).incrementRevision(projectId);
    }

    @Test
    void confirming_against_a_stale_revision_is_refused_and_records_no_sign_off() {
        // The case that matters most for a team: B edits Pages (clearing its
        // confirmation), then A — still looking at the old Pages — presses
        // Confirm. Without this check A would sign off on content A never saw.
        assertThatThrownBy(() -> service.confirmSection(projectId, AxRenewalSection.PAGES, STALE, developer))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT));

        verify(confirmations, never()).upsert(any(), any(), any(), any());
        verifyNoInteractions(audit);
    }

    @Test
    void confirming_against_the_current_revision_records_it_without_bumping() {
        when(confirmations.find(projectId, AxRenewalSection.PAGES))
                .thenReturn(Optional.of(new AxRenewalConfirmation(
                        projectId,
                        AxRenewalSection.PAGES,
                        Instant.EPOCH,
                        developer.id(),
                        AxRenewalConfirmation.VIA_DEVELOPER_ROLE)));

        service.confirmSection(projectId, AxRenewalSection.PAGES, CURRENT, developer);

        verify(projects).lockRevision(projectId);
        verify(confirmations).upsert(projectId, AxRenewalSection.PAGES, developer.id(), AxRenewalConfirmation.VIA_DEVELOPER_ROLE);
        // A confirmation changes no plan content, so it must not make a
        // teammate's open copy stale.
        verify(projects, never()).incrementRevision(any());
    }

    @Test
    void reopening_a_section_is_not_revision_checked() {
        // Reopen only withdraws a sign-off; there is no content in it that a
        // stale copy could overwrite, so it takes no revision at all.

        service.reopenSection(projectId, AxRenewalSection.PAGES, developer);

        verify(projects, never()).lockRevision(any());
        verify(projects, never()).incrementRevision(any());
        verify(confirmations).clear(projectId, AxRenewalSection.PAGES);
    }

    private AxRenewalProject project() {
        return new AxRenewalProject(
                projectId,
                "Legacy Portal",
                null,
                AxRenewalProject.STATUS_PLANNING,
                null,
                null,
                null,
                null,
                null,
                null,
                Instant.EPOCH,
                Instant.EPOCH,
                CURRENT);
    }
}
