package com.coc.docs.axrenewal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

/**
 * {@link AxRenewalImplementationRunner#cancel} — the real subprocess-kill
 * this feature had no way to do before (ADR-049). {@link
 * AxRenewalCodeGenerator} is mocked here: its real contract is that {@code
 * generate}'s third argument is called with a real, live {@link Process} the
 * instant one exists, and that it blocks until that process ends — this test
 * fulfils exactly that contract with a small, portable command instead of a
 * real Claude Code CLI, the same substitution {@code AxRenewalLocalRunnerTest}
 * already makes for a different long-lived process (ADR-048).
 */
class AxRenewalImplementationRunnerTest {

    private static boolean windows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    /** A real process this test can register and kill, without depending on npm/the Claude CLI being installed. */
    private static Process spawnIdleProcess() throws IOException {
        List<String> command = windows()
                ? List.of("cmd.exe", "/c", "ping -n 30 127.0.0.1 >nul")
                : List.of("sh", "-c", "sleep 30");
        return new ProcessBuilder(command).start();
    }

    private static void waitUntil(BooleanSupplier condition) {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
        while (!condition.getAsBoolean()) {
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("condition did not become true within 10s");
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
    }

    @Test
    void cancel_returns_false_when_this_run_has_no_live_process() {
        AxRenewalImplementationRunner runner =
                new AxRenewalImplementationRunner(
                        mock(AxRenewalCodeGenerator.class), mock(AxRenewalImplementationRepository.class), event -> {});

        assertThat(runner.cancel(UUID.randomUUID())).isFalse();
    }

    @Test
    void cancel_kills_the_tracked_process_and_the_run_is_marked_cancelled_not_failed() throws Exception {
        AxRenewalCodeGenerator generator = mock(AxRenewalCodeGenerator.class);
        AxRenewalImplementationRepository implementations = mock(AxRenewalImplementationRepository.class);
        AxRenewalImplementationRunner runner = new AxRenewalImplementationRunner(generator, implementations, event -> {});
        UUID runId = UUID.randomUUID();
        when(implementations.markStagingIfGenerating(eq(runId), any())).thenReturn(true);

        CountDownLatch processRegistered = new CountDownLatch(1);
        when(generator.generate(any(), any(), any(), any(), any(), any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Consumer<Process> onStarted = invocation.getArgument(3);
            Process process = spawnIdleProcess();
            onStarted.accept(process);
            processRegistered.countDown();
            process.waitFor(15, TimeUnit.SECONDS); // unblocks the moment cancel() kills it
            return Optional.empty();
        });

        // @Async has no proxy in a plain unit test, so this call runs on
        // whichever thread invokes it -- run it on its own thread so the
        // test's own thread is free to call cancel() concurrently, the way
        // an HTTP request actually would while generation is in flight.
        Thread generation = new Thread(() -> runner.runGeneration(
                runId, AxRenewalImplementationRun.RUN_KIND_PLAN, "a confirmed plan", "/tmp/legacy-source"));
        generation.start();
        assertThat(processRegistered.await(5, TimeUnit.SECONDS)).isTrue();

        assertThat(runner.cancel(runId)).isTrue();
        generation.join(TimeUnit.SECONDS.toMillis(15));

        verify(implementations).endIfStatus(runId, AxRenewalImplementationRun.STATUS_GENERATING, AxRenewalImplementationRun.STATUS_CANCELLED, null);
        verify(implementations, never())
                .endIfStatus(eq(runId), any(), eq(AxRenewalImplementationRun.STATUS_FAILED), any());
    }

    @Test
    void a_run_that_fails_on_its_own_without_ever_being_cancelled_is_still_reported_as_a_real_failure()
            throws Exception {
        AxRenewalCodeGenerator generator = mock(AxRenewalCodeGenerator.class);
        AxRenewalImplementationRepository implementations = mock(AxRenewalImplementationRepository.class);
        AxRenewalImplementationRunner runner = new AxRenewalImplementationRunner(generator, implementations, event -> {});
        UUID runId = UUID.randomUUID();
        when(implementations.markStagingIfGenerating(eq(runId), any())).thenReturn(true);

        when(generator.generate(any(), any(), any(), any(), any(), any())).thenReturn(Optional.empty());

        runner.runGeneration(runId, AxRenewalImplementationRun.RUN_KIND_PLAN, "a confirmed plan", "/tmp/legacy-source");

        verify(implementations)
                .endIfStatus(eq(runId), eq(AxRenewalImplementationRun.STATUS_GENERATING), eq(AxRenewalImplementationRun.STATUS_FAILED), any());
        verify(implementations, never())
                .endIfStatus(any(), any(), eq(AxRenewalImplementationRun.STATUS_CANCELLED), any());
    }

    @Test
    void cancel_is_a_one_shot_per_process_and_a_second_call_after_it_ended_reports_nothing_to_kill()
            throws Exception {
        AxRenewalCodeGenerator generator = mock(AxRenewalCodeGenerator.class);
        AxRenewalImplementationRepository implementations = mock(AxRenewalImplementationRepository.class);
        AxRenewalImplementationRunner runner = new AxRenewalImplementationRunner(generator, implementations, event -> {});
        UUID runId = UUID.randomUUID();
        when(implementations.markStagingIfGenerating(eq(runId), any())).thenReturn(true);

        CountDownLatch processRegistered = new CountDownLatch(1);
        when(generator.generate(any(), any(), any(), any(), any(), any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Consumer<Process> onStarted = invocation.getArgument(3);
            Process process = spawnIdleProcess();
            onStarted.accept(process);
            processRegistered.countDown();
            process.waitFor(15, TimeUnit.SECONDS);
            return Optional.empty();
        });

        Thread generation = new Thread(() -> runner.runGeneration(
                runId, AxRenewalImplementationRun.RUN_KIND_PLAN, "a confirmed plan", "/tmp/legacy-source"));
        generation.start();
        assertThat(processRegistered.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(runner.cancel(runId)).isTrue();
        generation.join(TimeUnit.SECONDS.toMillis(15));

        waitUntil(() -> !generation.isAlive());
        assertThat(runner.cancel(runId)).isFalse();
    }

    // -----------------------------------------------------------------------
    // Compare-and-set (ADR-073): a run closed by someone else — a Cancel with
    // no process to reach, or startup recovery on any backend sharing the
    // database — stays closed. The runner discards its own work instead.
    // -----------------------------------------------------------------------

    @Test
    void a_run_already_closed_before_it_starts_is_never_generated_and_leaves_no_staging_copy() {
        AxRenewalCodeGenerator generator = mock(AxRenewalCodeGenerator.class);
        AxRenewalImplementationRepository implementations = mock(AxRenewalImplementationRepository.class);
        AxRenewalImplementationRunner runner = new AxRenewalImplementationRunner(generator, implementations, event -> {});
        UUID runId = UUID.randomUUID();
        ArgumentCaptor<String> staging = ArgumentCaptor.forClass(String.class);
        when(implementations.markStagingIfGenerating(eq(runId), staging.capture())).thenReturn(false);

        runner.runGeneration(runId, AxRenewalImplementationRun.RUN_KIND_PLAN, "a confirmed plan", "/tmp/legacy-source");

        verify(generator, never()).generate(any(), any(), any(), any(), any(), any());
        verify(implementations, never()).endIfStatus(any(), any(), any(), any());
        assertThat(Path.of(staging.getValue())).doesNotExist();
    }

    @Test
    void a_run_closed_while_it_was_generating_discards_its_result_instead_of_going_to_review() {
        AxRenewalCodeGenerator generator = mock(AxRenewalCodeGenerator.class);
        AxRenewalImplementationRepository implementations = mock(AxRenewalImplementationRepository.class);
        AxRenewalImplementationRunner runner = new AxRenewalImplementationRunner(generator, implementations, event -> {});
        UUID runId = UUID.randomUUID();
        ArgumentCaptor<String> staging = ArgumentCaptor.forClass(String.class);
        when(implementations.markStagingIfGenerating(eq(runId), staging.capture())).thenReturn(true);
        when(generator.generate(any(), any(), any(), any(), any(), any()))
                .thenReturn(Optional.of(new AxRenewalCodeGenerator.Result(List.of(
                        new AxRenewalCodeGenerator.GeneratedFile("src/App.tsx", "modify", "a", "b")))));
        // Someone else closed it meanwhile: the claim on "awaiting review" fails.
        when(implementations.transitionIfStatus(
                        runId, AxRenewalImplementationRun.STATUS_GENERATING, AxRenewalImplementationRun.STATUS_AWAITING_REVIEW))
                .thenReturn(false);

        runner.runGeneration(runId, AxRenewalImplementationRun.RUN_KIND_PLAN, "a confirmed plan", "/tmp/legacy-source");

        verify(implementations).deleteGeneratedChanges(runId);
        assertThat(Path.of(staging.getValue())).doesNotExist();
    }

    @Test
    void a_run_still_generating_goes_to_review_and_keeps_its_changes() {
        AxRenewalCodeGenerator generator = mock(AxRenewalCodeGenerator.class);
        AxRenewalImplementationRepository implementations = mock(AxRenewalImplementationRepository.class);
        AxRenewalImplementationRunner runner = new AxRenewalImplementationRunner(generator, implementations, event -> {});
        UUID runId = UUID.randomUUID();
        ArgumentCaptor<String> staging = ArgumentCaptor.forClass(String.class);
        when(implementations.markStagingIfGenerating(eq(runId), staging.capture())).thenReturn(true);
        when(generator.generate(any(), any(), any(), any(), any(), any()))
                .thenReturn(Optional.of(new AxRenewalCodeGenerator.Result(List.of(
                        new AxRenewalCodeGenerator.GeneratedFile("src/App.tsx", "modify", "a", "b")))));
        when(implementations.transitionIfStatus(
                        runId, AxRenewalImplementationRun.STATUS_GENERATING, AxRenewalImplementationRun.STATUS_AWAITING_REVIEW))
                .thenReturn(true);

        try {
            runner.runGeneration(
                    runId, AxRenewalImplementationRun.RUN_KIND_PLAN, "a confirmed plan", "/tmp/legacy-source");

            verify(implementations, never()).deleteGeneratedChanges(any());
            // The staging copy is what Approve applies from — it must survive.
            assertThat(Path.of(staging.getValue())).isDirectory();
        } finally {
            AxRenewalImplementationRunner.deleteQuietly(Path.of(staging.getValue()));
        }
    }

    // --- "Ready for review" (ADR-079): announced once the run is there --------

    /** A runner whose announcements land in {@code published}, with one file of output ready. */
    private static AxRenewalImplementationRunner announcingRunner(
            AxRenewalImplementationRepository implementations, List<Object> published, boolean generates) {
        AxRenewalCodeGenerator generator = mock(AxRenewalCodeGenerator.class);
        when(implementations.markStagingIfGenerating(any(), any())).thenReturn(true);
        when(generator.generate(any(), any(), any(), any(), any(), any()))
                .thenReturn(generates
                        ? Optional.of(new AxRenewalCodeGenerator.Result(List.of(
                                new AxRenewalCodeGenerator.GeneratedFile("src/App.tsx", "modify", "a", "b"))))
                        : Optional.empty());
        return new AxRenewalImplementationRunner(generator, implementations, published::add);
    }

    @Test
    void a_run_that_reaches_review_is_announced_to_its_project_starter_included() {
        AxRenewalImplementationRepository implementations = mock(AxRenewalImplementationRepository.class);
        List<Object> published = new java.util.ArrayList<>();
        AxRenewalImplementationRunner runner = announcingRunner(implementations, published, true);
        UUID runId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        when(implementations.transitionIfStatus(
                        runId, AxRenewalImplementationRun.STATUS_GENERATING, AxRenewalImplementationRun.STATUS_AWAITING_REVIEW))
                .thenReturn(true);
        when(implementations.findById(runId)).thenReturn(Optional.of(new AxRenewalImplementationRun(
                runId, projectId, AxRenewalImplementationRun.STATUS_AWAITING_REVIEW, null, null, Instant.EPOCH,
                UUID.randomUUID(), null, null, null, null)));
        ArgumentCaptor<String> staging = ArgumentCaptor.forClass(String.class);
        when(implementations.markStagingIfGenerating(eq(runId), staging.capture())).thenReturn(true);

        try {
            runner.runGeneration(runId, AxRenewalImplementationRun.RUN_KIND_PLAN, "a confirmed plan", "/tmp/legacy-source");
        } finally {
            AxRenewalImplementationRunner.deleteQuietly(Path.of(staging.getValue()));
        }

        // Nobody skipped: the starter has been waiting minutes for this.
        assertThat(published).containsExactly(new AxRenewalRunReadyEvent(projectId, runId, null));
    }

    @Test
    void a_result_discarded_because_the_run_was_closed_announces_nothing() {
        AxRenewalImplementationRepository implementations = mock(AxRenewalImplementationRepository.class);
        List<Object> published = new java.util.ArrayList<>();
        AxRenewalImplementationRunner runner = announcingRunner(implementations, published, true);
        UUID runId = UUID.randomUUID();
        when(implementations.transitionIfStatus(any(), any(), any())).thenReturn(false);

        runner.runGeneration(runId, AxRenewalImplementationRun.RUN_KIND_PLAN, "a confirmed plan", "/tmp/legacy-source");

        assertThat(published).isEmpty();
    }

    @Test
    void a_failed_run_announces_nothing() {
        AxRenewalImplementationRepository implementations = mock(AxRenewalImplementationRepository.class);
        List<Object> published = new java.util.ArrayList<>();
        AxRenewalImplementationRunner runner = announcingRunner(implementations, published, false);

        runner.runGeneration(
                UUID.randomUUID(), AxRenewalImplementationRun.RUN_KIND_PLAN, "a confirmed plan", "/tmp/legacy-source");

        assertThat(published).isEmpty();
        verify(implementations, never()).transitionIfStatus(any(), any(), any());
    }

    // --- Live progress (ADR-082): tracked while generating, gone after ------

    @Test
    void a_run_s_progress_is_readable_while_it_generates_and_gone_once_it_has_an_outcome() {
        AxRenewalCodeGenerator generator = mock(AxRenewalCodeGenerator.class);
        AxRenewalImplementationRepository implementations = mock(AxRenewalImplementationRepository.class);
        AxRenewalImplementationRunner runner = new AxRenewalImplementationRunner(generator, implementations, event -> {});
        UUID runId = UUID.randomUUID();
        when(implementations.markStagingIfGenerating(eq(runId), any())).thenReturn(true);
        List<Optional<AxRenewalGenerationProgress.Snapshot>> seenMidRun = new java.util.ArrayList<>();
        when(generator.generate(any(), any(), any(), any(), any(), any())).thenAnswer(invocation -> {
            AxRenewalGenerationProgress.Listener progress = invocation.getArgument(5);
            progress.step(AxRenewalGenerationProgress.STEP_WORKING);
            progress.activities(List.of(new AxRenewalGenerationProgress.Activity(
                    Instant.EPOCH, "edit", "Editing src/App.tsx", "src/App.tsx")));
            seenMidRun.add(runner.progress(runId));
            return Optional.empty();
        });

        runner.runGeneration(runId, AxRenewalImplementationRun.RUN_KIND_PLAN, "a confirmed plan", "/tmp/legacy-source");

        assertThat(seenMidRun).singleElement().satisfies(snapshot -> assertThat(snapshot).hasValueSatisfying(s -> {
            assertThat(s.step()).isEqualTo(AxRenewalGenerationProgress.STEP_WORKING);
            assertThat(s.filesChanged()).isEqualTo(1);
        }));
        assertThat(runner.progress(runId)).isEmpty();
    }

    @Test
    void a_run_closed_before_it_starts_leaves_no_progress_behind() {
        AxRenewalImplementationRepository implementations = mock(AxRenewalImplementationRepository.class);
        AxRenewalImplementationRunner runner = new AxRenewalImplementationRunner(
                mock(AxRenewalCodeGenerator.class), implementations, event -> {});
        UUID runId = UUID.randomUUID();
        when(implementations.markStagingIfGenerating(eq(runId), any())).thenReturn(false);

        runner.runGeneration(runId, AxRenewalImplementationRun.RUN_KIND_PLAN, "a confirmed plan", "/tmp/legacy-source");

        assertThat(runner.progress(runId)).isEmpty();
    }

    // --- deleteStagingCopy: never deletes what is not its own -------------

    @Test
    void deletes_one_of_its_own_staging_copies() throws IOException {
        Path staging = Files.createTempDirectory(AxRenewalImplementationRunner.STAGING_PREFIX);
        Files.writeString(staging.resolve("Generated.java"), "class Generated {}");

        AxRenewalImplementationRunner.deleteStagingCopy(staging.toString());

        assertThat(staging).doesNotExist();
    }

    @Test
    void never_deletes_a_path_that_is_not_one_of_its_own_staging_copies(@TempDir Path elsewhere) throws IOException {
        // The path comes back from the database; one changed there — to the
        // team's real output folder, say — must not be deleted.
        Path notStaging = Files.createDirectory(elsewhere.resolve("renewed-app"));
        Files.writeString(notStaging.resolve("keep.txt"), "the team's own code");

        AxRenewalImplementationRunner.deleteStagingCopy(notStaging.toString());

        assertThat(notStaging.resolve("keep.txt")).exists();
    }

    @Test
    void ignores_a_missing_or_empty_staging_path() {
        AxRenewalImplementationRunner.deleteStagingCopy(null);
        AxRenewalImplementationRunner.deleteStagingCopy(
                Path.of(System.getProperty("java.io.tmpdir"), AxRenewalImplementationRunner.STAGING_PREFIX + "gone")
                        .toString());
    }

    @Test
    void a_generating_run_is_only_considered_dead_well_after_the_cli_s_own_timeout() {
        // The one rule startup recovery may apply on a database several
        // developers' backends share: older than any real attempt can last.
        assertThat(AxRenewalImplementationRunner.GENERATING_ORPHANED_AFTER)
                .isGreaterThanOrEqualTo(AxRenewalCodeGenerator.TIMEOUT.multipliedBy(2));
    }
}
