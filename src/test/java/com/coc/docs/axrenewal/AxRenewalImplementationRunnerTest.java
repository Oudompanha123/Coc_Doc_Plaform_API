package com.coc.docs.axrenewal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
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
                new AxRenewalImplementationRunner(mock(AxRenewalCodeGenerator.class), mock(AxRenewalImplementationRepository.class));

        assertThat(runner.cancel(UUID.randomUUID())).isFalse();
    }

    @Test
    void cancel_kills_the_tracked_process_and_the_run_is_marked_cancelled_not_failed() throws Exception {
        AxRenewalCodeGenerator generator = mock(AxRenewalCodeGenerator.class);
        AxRenewalImplementationRepository implementations = mock(AxRenewalImplementationRepository.class);
        AxRenewalImplementationRunner runner = new AxRenewalImplementationRunner(generator, implementations);
        UUID runId = UUID.randomUUID();

        CountDownLatch processRegistered = new CountDownLatch(1);
        when(generator.generate(any(), any(), any(), any(), any())).thenAnswer(invocation -> {
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

        verify(implementations).markCancelled(runId);
        verify(implementations, never()).markFailed(eq(runId), any());
    }

    @Test
    void a_run_that_fails_on_its_own_without_ever_being_cancelled_is_still_reported_as_a_real_failure()
            throws Exception {
        AxRenewalCodeGenerator generator = mock(AxRenewalCodeGenerator.class);
        AxRenewalImplementationRepository implementations = mock(AxRenewalImplementationRepository.class);
        AxRenewalImplementationRunner runner = new AxRenewalImplementationRunner(generator, implementations);
        UUID runId = UUID.randomUUID();

        when(generator.generate(any(), any(), any(), any(), any())).thenReturn(Optional.empty());

        runner.runGeneration(runId, AxRenewalImplementationRun.RUN_KIND_PLAN, "a confirmed plan", "/tmp/legacy-source");

        verify(implementations).markFailed(eq(runId), any());
        verify(implementations, never()).markCancelled(any());
    }

    @Test
    void cancel_is_a_one_shot_per_process_and_a_second_call_after_it_ended_reports_nothing_to_kill()
            throws Exception {
        AxRenewalCodeGenerator generator = mock(AxRenewalCodeGenerator.class);
        AxRenewalImplementationRepository implementations = mock(AxRenewalImplementationRepository.class);
        AxRenewalImplementationRunner runner = new AxRenewalImplementationRunner(generator, implementations);
        UUID runId = UUID.randomUUID();

        CountDownLatch processRegistered = new CountDownLatch(1);
        when(generator.generate(any(), any(), any(), any(), any())).thenAnswer(invocation -> {
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
}
