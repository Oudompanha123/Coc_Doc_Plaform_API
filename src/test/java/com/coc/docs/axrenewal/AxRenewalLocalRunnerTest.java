package com.coc.docs.axrenewal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The real lifecycle logic {@link AxRenewalLocalRunner} owns — reader
 * thread/log capture/"ready" URL detection/state machine/stop — exercised
 * against a small, controlled command via the package-private {@code
 * start(UUID, List, Path)} seam, rather than a real {@code npm install}
 * (mirrors {@link AxRenewalCodeGeneratorTest}'s own {@code copyTree}/{@code
 * diffAgainstSource} testability seams).
 *
 * <p>// req: ADR-048
 */
class AxRenewalLocalRunnerTest {

    private final AxRenewalLocalRunner runner = new AxRenewalLocalRunner();

    private static boolean windows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    /** A command any of these tests can run without depending on npm/Node being installed — prints a fake "ready" line, then idles until killed. */
    private static List<String> echoThenIdle(String readyLine) {
        return windows()
                ? List.of("cmd.exe", "/c", "echo " + readyLine + " && ping -n 30 127.0.0.1 >nul")
                : List.of("sh", "-c", "echo " + readyLine + "; sleep 30");
    }

    /** Polls a condition that becomes true asynchronously off a background thread — this test suite's one wait primitive, since Awaitility is not a dependency here. */
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
    void reports_starting_then_running_once_the_process_announces_its_own_url(@TempDir Path cwd) throws IOException {
        UUID projectId = UUID.randomUUID();
        try {
            runner.start(projectId, echoThenIdle("http://localhost:4123"), cwd);

            waitUntil(() -> runner.status(projectId).state().equals(AxRenewalLocalRunner.Status.STATE_RUNNING));

            AxRenewalLocalRunner.Status status = runner.status(projectId);
            assertThat(status.url()).isEqualTo("http://localhost:4123");
            assertThat(status.log()).anyMatch(line -> line.contains("http://localhost:4123"));
            assertThat(status.startedAt()).isNotNull();
        } finally {
            runner.stop(projectId);
        }
    }

    @Test
    void refuses_to_start_a_second_process_for_the_same_project_while_one_is_alive(@TempDir Path cwd)
            throws IOException {
        UUID projectId = UUID.randomUUID();
        try {
            runner.start(projectId, echoThenIdle("http://localhost:4124"), cwd);

            assertThatThrownBy(() -> runner.start(projectId, echoThenIdle("http://localhost:4125"), cwd))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("already in progress");
        } finally {
            runner.stop(projectId);
        }
    }

    @Test
    void stop_actually_ends_the_process_and_status_reports_stopped(@TempDir Path cwd) throws IOException {
        UUID projectId = UUID.randomUUID();
        runner.start(projectId, echoThenIdle("http://localhost:4126"), cwd);
        waitUntil(() -> runner.status(projectId).state().equals(AxRenewalLocalRunner.Status.STATE_RUNNING));

        runner.stop(projectId);

        waitUntil(() -> !runner.isRunning(projectId));
        assertThat(runner.status(projectId).state()).isEqualTo(AxRenewalLocalRunner.Status.STATE_STOPPED);
        // isAlive() can report false on Windows a moment before the OS has
        // actually released this process's handle on @TempDir's own
        // directory (its cwd) -- without this, @TempDir's own cleanup can
        // intermittently fail with "used by another process" right after
        // this method returns. A bounded grace period, not a real assertion.
        waitUntilDeletable(cwd);
    }

    /** Retries a harmless create-then-delete probe until it succeeds, or gives up — see the comment at its one call site for why this exists. */
    private static void waitUntilDeletable(Path dir) {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(5));
        while (Instant.now().isBefore(deadline)) {
            try {
                Path probe = Files.createTempFile(dir, "probe", ".tmp");
                Files.delete(probe);
                return;
            } catch (IOException e) {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    @Test
    void a_project_that_never_started_reports_stopped_with_an_empty_log() {
        AxRenewalLocalRunner.Status status = runner.status(UUID.randomUUID());
        assertThat(status.state()).isEqualTo(AxRenewalLocalRunner.Status.STATE_STOPPED);
        assertThat(status.log()).isEmpty();
        assertThat(status.url()).isNull();
        assertThat(status.startedAt()).isNull();
    }

    @Test
    void a_command_that_exits_with_a_non_zero_code_is_reported_failed(@TempDir Path cwd) throws IOException {
        UUID projectId = UUID.randomUUID();
        List<String> failingCommand = windows() ? List.of("cmd.exe", "/c", "exit 1") : List.of("sh", "-c", "exit 1");

        runner.start(projectId, failingCommand, cwd);

        waitUntil(() -> runner.status(projectId).state().equals(AxRenewalLocalRunner.Status.STATE_FAILED));
    }
}
