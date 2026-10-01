package com.coc.docs.axrenewal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Rebuild reproduces the same result deterministically (ADR-056), instead
 * of asking the model again every time: once a project has a real, applied
 * result and the confirmed plan has not changed since, every subsequent
 * Rebuild replays that exact result rather than risking a different one —
 * genuine non-determinism in what a fresh model call returns, stacked on
 * top of every team member's own machine having its own copy of the legacy
 * source at its own path.
 *
 * <p>Every test here confirms the same fixed plan (one decided Stack move
 * layer, nothing else) — {@link #PLAN_HASH} is exactly what {@code
 * AxRenewalService.sha256Hex} produces for that plan's text, computed once
 * so tests can say "this prior run matches the current plan" or "this one
 * doesn't" without re-deriving {@code buildConfirmedPlanDescription}'s own
 * formatting.
 *
 * <p>// req: ADR-037, ADR-044, ADR-056
 */
class AxRenewalRebuildReplayTest {

    private static final String PLAN_HASH =
            AxRenewalService.sha256Hex("Stack move:\n- runtime: PHP 5.6 -> Node 20\n");

    private AxRenewalProjectRepository projects;
    private AxRenewalPlanRepository plan;
    private AxRenewalDestinationRepository destinations;
    private AxRenewalConfirmationRepository confirmations;
    private AxRenewalImplementationRepository implementations;
    private AxRenewalCodeGenerator generator;
    private AuditRepository audit;
    private AxRenewalService service;

    private final UUID projectId = UUID.randomUUID();
    private final CurrentUser actor = TestCallers.user(AppRole.SUPER_ADMIN);

    @BeforeEach
    void setUp() {
        projects = mock(AxRenewalProjectRepository.class);
        plan = mock(AxRenewalPlanRepository.class);
        destinations = mock(AxRenewalDestinationRepository.class);
        confirmations = mock(AxRenewalConfirmationRepository.class);
        implementations = mock(AxRenewalImplementationRepository.class);
        generator = mock(AxRenewalCodeGenerator.class);
        audit = mock(AuditRepository.class);

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

        // Every section confirmed, and the plan has exactly one real decided
        // layer -- "confirmed" is not the same as "describes a concrete
        // change", and startImplementation refuses a blank plan regardless
        // of the path. This exact combination is what PLAN_HASH above is a
        // hash of.
        for (AxRenewalSection section : AxRenewalSection.values()) {
            when(confirmations.find(projectId, section)).thenReturn(Optional.of(new AxRenewalConfirmation(
                    projectId, section, Instant.EPOCH, actor.id(), AxRenewalConfirmation.VIA_SUPER_ADMIN, "Admin")));
        }
        when(plan.findStackLayers(projectId)).thenReturn(List.of(new AxRenewalStackLayer(
                UUID.randomUUID(), projectId, "runtime", "PHP 5.6", "Node 20", 0, Instant.EPOCH, actor.id())));
        when(plan.findPageMappings(projectId)).thenReturn(List.of());
        when(plan.findDbChanges(projectId)).thenReturn(List.of());
        when(plan.findArchitecture(projectId)).thenReturn(Optional.empty());

        when(implementations.findActive(projectId)).thenReturn(Optional.empty());
        // Round-trips whatever startImplementation inserts, the same shape
        // every real repository call would return. Two different overloads,
        // mocked separately, each actually reached by a different path:
        // replayAppliedRun still calls the 5-arg overload directly (it only
        // ever reproduces a plan-kind run), while beginGeneration now always
        // calls the 7-arg overload (ADR-062 Phase 2), even for a fresh
        // plan-kind run -- a mock does not run either method's real body, so
        // both must be stubbed for whichever test reaches them.
        when(implementations.insert(any(), any(), any(), any(), any())).thenAnswer(inv -> new AxRenewalImplementationRun(
                UUID.randomUUID(),
                projectId,
                inv.getArgument(3),
                inv.getArgument(2),
                null,
                Instant.EPOCH,
                actor.id(),
                null,
                null,
                null,
                inv.getArgument(4)));
        when(implementations.insert(any(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(inv -> new AxRenewalImplementationRun(
                        UUID.randomUUID(),
                        projectId,
                        inv.getArgument(3),
                        inv.getArgument(2),
                        null,
                        Instant.EPOCH,
                        actor.id(),
                        null,
                        null,
                        null,
                        inv.getArgument(4),
                        inv.getArgument(5),
                        inv.getArgument(6)));
        // The Phase-1 placeholder's own 3-arg overload -- a different
        // overload Mockito matches separately from the two above.
        when(implementations.insert(any(), any(), any())).thenAnswer(inv -> new AxRenewalImplementationRun(
                UUID.randomUUID(),
                projectId,
                AxRenewalImplementationRun.STATUS_RUNNING,
                inv.getArgument(2),
                null,
                Instant.EPOCH,
                actor.id(),
                null,
                null,
                null,
                null));

        service = new AxRenewalService(
                projects,
                mock(AxRenewalAnalysisRepository.class),
                plan,
                mock(AxRenewalDatabaseConnectionRepository.class),
                destinations,
                confirmations,
                implementations,
                mock(AxRenewalCommentRepository.class),
                TestMembers.everyone(),
                mock(com.coc.docs.account.AccountRepository.class),
                audit,
                new ObjectMapper(),
                mock(LocalClaudeAnalyzer.class),
                mock(LocalClaudeChat.class),
                new AxRenewalProperties(null, null),
                generator,
                mock(AxRenewalImplementationRunner.class),
                mock(AxRenewalLocalRunner.class),
                mock(AxRenewalTestRunner.class),
                event -> {});
    }

    private void filesystemDestination() {
        when(destinations.find(projectId))
                .thenReturn(Optional.of(new AxRenewalDestination(
                        projectId, AxRenewalDestination.KIND_FILESYSTEM_PATH, "/dest", null, null, Instant.EPOCH,
                        actor.id())));
    }

    private AxRenewalImplementationRun priorRun(UUID runId, String status, String confirmedPlanHash) {
        return new AxRenewalImplementationRun(
                runId, projectId, status, null, null, Instant.EPOCH, actor.id(), Instant.EPOCH, null, null,
                confirmedPlanHash);
    }

    @Test
    void a_first_build_with_no_prior_run_never_attempts_a_replay() {
        filesystemDestination();
        // Empty for startImplementation's own replay check, then a row for
        // recordPlaceholderCompletion's unrelated, pre-existing re-read of
        // what it just inserted -- two calls to the same method, only the
        // first of which this test is actually about.
        when(implementations.findLatest(projectId))
                .thenReturn(
                        Optional.empty(),
                        Optional.of(priorRun(UUID.randomUUID(), AxRenewalImplementationRun.STATUS_COMPLETED, null)));
        when(generator.canGenerate()).thenReturn(false); // no CLI -> placeholder; the point is that no replay is attempted either

        service.startImplementation(projectId, null, actor);

        verify(implementations, never()).findGeneratedChanges(any());
        verify(implementations, never()).insertGeneratedChanges(any(), anyList());
        verify(audit, never())
                .append(any(), any(), eq(AuditAction.AX_RENEWAL_IMPLEMENTATION_REPLAY), any(), any(), any());
    }

    @Test
    void replays_the_exact_prior_result_when_the_confirmed_plan_has_not_changed() {
        filesystemDestination();
        UUID priorRunId = UUID.randomUUID();
        AxRenewalGeneratedChange priorChange = new AxRenewalGeneratedChange(
                UUID.randomUUID(), priorRunId, "src/app.js", AxRenewalGeneratedChange.KIND_MODIFY, "old", "new", 0);
        when(implementations.findLatest(projectId))
                .thenReturn(Optional.of(priorRun(priorRunId, AxRenewalImplementationRun.STATUS_APPLIED, PLAN_HASH)));
        when(implementations.findGeneratedChanges(priorRunId)).thenReturn(List.of(priorChange));

        AxRenewalImplementationRun result = service.startImplementation(projectId, null, actor);

        assertThat(result.status()).isEqualTo(AxRenewalImplementationRun.STATUS_AWAITING_REVIEW);
        assertThat(result.confirmedPlanHash()).isEqualTo(PLAN_HASH);

        // The model is never asked -- this is the whole point of replaying.
        verify(generator, never()).generate(any(), any(), any(), any(), any());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AxRenewalImplementationRepository.GeneratedChangeInput>> changesCaptor =
                ArgumentCaptor.forClass(List.class);
        verify(implementations).insertGeneratedChanges(any(), changesCaptor.capture());
        assertThat(changesCaptor.getValue()).singleElement().satisfies(c -> {
            assertThat(c.path()).isEqualTo("src/app.js");
            assertThat(c.afterContent()).isEqualTo("new");
        });

        ArgumentCaptor<JsonNode> detail = ArgumentCaptor.forClass(JsonNode.class);
        verify(audit)
                .append(
                        eq(actor.id()),
                        eq(actor.email()),
                        eq(AuditAction.AX_RENEWAL_IMPLEMENTATION_REPLAY),
                        eq(AuditAction.TARGET_AX_RENEWAL_PROJECT),
                        eq(projectId),
                        detail.capture());
        assertThat(detail.getValue().path("replayedFromRunId").asText()).isEqualTo(priorRunId.toString());
    }

    @Test
    void a_changed_plan_generates_afresh_rather_than_replaying_a_stale_result() {
        filesystemDestination();
        UUID priorRunId = UUID.randomUUID();
        when(implementations.findLatest(projectId)).thenReturn(Optional.of(priorRun(
                priorRunId, AxRenewalImplementationRun.STATUS_APPLIED, "a-hash-from-a-different-plan-entirely")));
        when(generator.canGenerate()).thenReturn(false); // placeholder branch; still proves no replay happens

        service.startImplementation(projectId, null, actor);

        verify(implementations, never()).findGeneratedChanges(priorRunId);
        verify(implementations, never()).insertGeneratedChanges(any(), anyList());
        verify(audit, never())
                .append(any(), any(), eq(AuditAction.AX_RENEWAL_IMPLEMENTATION_REPLAY), any(), any(), any());
    }

    @Test
    void a_prior_run_that_was_rejected_not_applied_is_not_replayed() {
        filesystemDestination();
        UUID priorRunId = UUID.randomUUID();
        when(implementations.findLatest(projectId))
                .thenReturn(Optional.of(priorRun(priorRunId, AxRenewalImplementationRun.STATUS_REJECTED, PLAN_HASH)));
        when(generator.canGenerate()).thenReturn(false);

        service.startImplementation(projectId, null, actor);

        verify(implementations, never()).findGeneratedChanges(priorRunId);
        verify(implementations, never()).insertGeneratedChanges(any(), anyList());
    }

    @Test
    void a_non_filesystem_destination_is_never_replayed_even_with_a_matching_hash() {
        when(destinations.find(projectId))
                .thenReturn(Optional.of(new AxRenewalDestination(
                        projectId, AxRenewalDestination.KIND_GIT_REPO, "git@example.com:x/y.git", null, null,
                        Instant.EPOCH, actor.id())));
        UUID priorRunId = UUID.randomUUID();
        when(implementations.findLatest(projectId))
                .thenReturn(Optional.of(priorRun(priorRunId, AxRenewalImplementationRun.STATUS_APPLIED, PLAN_HASH)));
        when(generator.canGenerate()).thenReturn(false);

        service.startImplementation(projectId, null, actor);

        verify(implementations, never()).findGeneratedChanges(priorRunId);
        verify(implementations, never()).insertGeneratedChanges(any(), anyList());
    }

    @Test
    void a_real_generate_run_records_the_confirmed_plan_hash_so_a_later_rebuild_can_replay_it(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path tmp) {
        filesystemDestination();
        when(implementations.findLatest(projectId)).thenReturn(Optional.empty());
        when(generator.canGenerate()).thenReturn(true);

        // beginGeneration defers the actual runner call to afterCommit, which
        // needs an active transaction synchronization scope to register
        // into -- real requests get one from @Transactional; this plain unit
        // test opens the same scope by hand, exactly to observe the
        // synchronous half (the row startImplementation itself returns).
        org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
        try {
            AxRenewalImplementationRun result = service.startImplementation(projectId, tmp.toString(), actor);

            assertThat(result.status()).isEqualTo(AxRenewalImplementationRun.STATUS_GENERATING);
            assertThat(result.confirmedPlanHash()).isEqualTo(PLAN_HASH);
        } finally {
            org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization();
        }
    }
}
