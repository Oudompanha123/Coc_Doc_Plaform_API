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
import org.junit.jupiter.api.BeforeEach;
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
 * <p>// req: ADR-048, ADR-083
 */
class AxRenewalLocalRunnerTest {

    /** This test's own pid directory — never this machine's real one (ADR-083). */
    @TempDir
    Path pidDir;

    private AxRenewalLocalRunner runner;

    @BeforeEach
    void setUp() {
        runner = new AxRenewalLocalRunner(pidDir);
    }

    /** The live root process the runner recorded for {@code projectId}, read from its pid file. */
    private ProcessHandle recordedRoot(UUID projectId) throws IOException {
        String[] parts = Files.readString(pidDir.resolve(projectId + ".pid")).trim().split(" ");
        return ProcessHandle.of(Long.parseLong(parts[0])).orElseThrow();
    }

    /** {@code root} and everything under it, as of now. */
    private static List<ProcessHandle> treeOf(ProcessHandle root) {
        List<ProcessHandle> tree = new java.util.ArrayList<>(root.descendants().toList());
        tree.add(root);
        return tree;
    }

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

    // --- ADR-083: a rerun gets its port back --------------------------------

    @Test
    void stop_returns_only_once_the_whole_process_tree_has_exited(@TempDir Path cwd) throws IOException {
        UUID projectId = UUID.randomUUID();
        runner.start(projectId, echoThenIdle("http://localhost:4130"), cwd);
        waitUntil(() -> runner.status(projectId).state().equals(AxRenewalLocalRunner.Status.STATE_RUNNING));
        List<ProcessHandle> tree = treeOf(recordedRoot(projectId));
        // The idle child (ping/sleep) is what would hold a port in real life.
        assertThat(tree).hasSizeGreaterThan(1);

        runner.stop(projectId);

        // No waiting here: stop() itself waited.
        assertThat(tree).noneMatch(ProcessHandle::isAlive);
        assertThat(pidDir.resolve(projectId + ".pid")).doesNotExist();
        waitUntilDeletable(cwd);
    }

    @Test
    void a_dev_server_an_earlier_backend_left_running_is_stopped_before_the_next_start(@TempDir Path cwd)
            throws IOException {
        UUID projectId = UUID.randomUUID();
        runner.start(projectId, echoThenIdle("http://localhost:4131"), cwd);
        waitUntil(() -> runner.status(projectId).state().equals(AxRenewalLocalRunner.Status.STATE_RUNNING));
        List<ProcessHandle> leftover = treeOf(recordedRoot(projectId));

        // The backend restarts without a graceful shutdown: a new runner, with
        // nothing in memory, but the same pid directory.
        AxRenewalLocalRunner afterRestart = new AxRenewalLocalRunner(pidDir);
        assertThat(afterRestart.status(projectId).state()).isEqualTo(AxRenewalLocalRunner.Status.STATE_STOPPED);
        try {
            afterRestart.start(projectId, echoThenIdle("http://localhost:4132"), cwd);

            assertThat(leftover).noneMatch(ProcessHandle::isAlive);
            waitUntil(() -> afterRestart.status(projectId).state().equals(AxRenewalLocalRunner.Status.STATE_RUNNING));
            assertThat(afterRestart.status(projectId).url()).isEqualTo("http://localhost:4132");
        } finally {
            afterRestart.stop(projectId);
            waitUntilDeletable(cwd);
        }
    }

    @Test
    void a_recorded_pid_that_now_belongs_to_another_process_is_never_touched(@TempDir Path cwd) throws IOException {
        UUID projectId = UUID.randomUUID();
        Process unrelated = new ProcessBuilder(echoThenIdle("unrelated")).directory(cwd.toFile()).start();
        try {
            // Its pid, but a start time that is not its own: a reused pid.
            Files.writeString(pidDir.resolve(projectId + ".pid"), unrelated.pid() + " 1");

            runner.stop(projectId);
            runner.start(projectId, echoThenIdle("http://localhost:4133"), cwd);

            assertThat(unrelated.isAlive()).isTrue();
        } finally {
            runner.stop(projectId);
            AxRenewalLocalRunner.killTree(unrelated.toHandle());
            waitUntilDeletable(cwd);
        }
    }

    @Test
    void a_record_that_cannot_be_read_does_not_stop_the_start(@TempDir Path cwd) throws IOException {
        UUID projectId = UUID.randomUUID();
        Files.writeString(pidDir.resolve(projectId + ".pid"), "not a pid");
        try {
            runner.start(projectId, echoThenIdle("http://localhost:4134"), cwd);

            waitUntil(() -> runner.status(projectId).state().equals(AxRenewalLocalRunner.Status.STATE_RUNNING));
        } finally {
            runner.stop(projectId);
            waitUntilDeletable(cwd);
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

    // --- forget (ADR-072): a deleted project leaves nothing in memory -------

    @Test
    void forget_drops_a_finished_run_s_state_and_captured_log(@TempDir Path cwd) throws IOException {
        UUID projectId = UUID.randomUUID();
        List<String> failing = windows()
                ? List.of("cmd.exe", "/c", "echo secret-looking output && exit 1")
                : List.of("sh", "-c", "echo secret-looking output; exit 1");
        runner.start(projectId, failing, cwd);
        waitUntil(() -> runner.status(projectId).state().equals(AxRenewalLocalRunner.Status.STATE_FAILED));

        runner.forget(projectId);

        // Indistinguishable from a project that never ran at all.
        AxRenewalLocalRunner.Status status = runner.status(projectId);
        assertThat(status.state()).isEqualTo(AxRenewalLocalRunner.Status.STATE_STOPPED);
        assertThat(status.log()).isEmpty();
    }

    @Test
    void forget_never_drops_a_live_process(@TempDir Path cwd) throws IOException {
        // Forgetting a live one would orphan a process nobody could stop.
        UUID projectId = UUID.randomUUID();
        runner.start(projectId, echoThenIdle("http://localhost:4127"), cwd);
        try {
            runner.forget(projectId);

            assertThat(runner.isRunning(projectId)).isTrue();
        } finally {
            runner.stop(projectId);
            waitUntil(() -> !runner.isRunning(projectId));
            waitUntilDeletable(cwd);
        }
    }

    // -----------------------------------------------------------------------
    // hasRuntimeErrorEvidence (ADR-062) -- a dev server rendering an
    // unhandled error for one request never exits the process, so `failed`
    // never fires for it; this is the real signal the AI Error Assistant
    // actually needs instead.
    // -----------------------------------------------------------------------

    @Test
    void recognizes_a_real_js_error_headline() {
        assertThat(AxRenewalLocalRunner.hasRuntimeErrorEvidence(
                        List.of("TypeError: Cannot read properties of undefined (reading 'role')")))
                .isTrue();
    }

    @Test
    void recognizes_a_real_5xx_request_log_line() {
        assertThat(AxRenewalLocalRunner.hasRuntimeErrorEvidence(List.of("GET / 500 in 4248ms"))).isTrue();
    }

    @Test
    void does_not_flag_a_clean_log_with_neither_signal() {
        assertThat(AxRenewalLocalRunner.hasRuntimeErrorEvidence(List.of("ready on http://localhost:3000", "GET / 200 in 12ms")))
                .isFalse();
    }

    @Test
    void does_not_flag_an_empty_log() {
        assertThat(AxRenewalLocalRunner.hasRuntimeErrorEvidence(List.of())).isFalse();
    }
}
