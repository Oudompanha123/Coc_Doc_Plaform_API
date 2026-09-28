package com.coc.docs.axrenewal;

import jakarta.annotation.PreDestroy;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Runs one project's applied destination as a live local dev server —
 * {@code npm install && npm run dev} as a supervised child process — for the
 * Build &rarr; Run Locally tab (ADR-048).
 *
 * <p>Deliberately in-memory only, unlike everything else this feature
 * persists: a live {@link Process} handle cannot survive a backend restart
 * regardless of what a database row claims, so writing one to Postgres would
 * be this feature's first invented fact rather than a reported one. One
 * running process per project ({@link #byProject}); starting again while one
 * is alive is refused by {@link #start}, matching the "one active thing" rule
 * {@code AxRenewalImplementationRepository#findActive} already enforces for
 * implementation runs.
 *
 * <p>// req: ADR-048
 */
@Component
public class AxRenewalLocalRunner {

    private static final Logger log = LoggerFactory.getLogger(AxRenewalLocalRunner.class);

    /** A rolling window, not a transcript — long enough to show what happened, short enough that a long-running dev server cannot grow this without bound. */
    private static final int MAX_LOG_LINES = 500;

    /** Matches a dev server's own "ready" announcement (Next.js, Vite, CRA all print exactly this shape) — the URL shown is read off this, never assumed from a hardcoded port. */
    private static final Pattern READY_URL = Pattern.compile("https?://(?:localhost|127\\.0\\.0\\.1):\\d+");

    private final Map<UUID, Running> byProject = new ConcurrentHashMap<>();

    /** One project's live process state, read by {@link #status} and written only by that process's own reader thread (and {@link #stop}). */
    public record Status(String state, List<String> log, String url, Instant startedAt) {

        public static final String STATE_STARTING = "starting";
        public static final String STATE_RUNNING = "running";
        public static final String STATE_STOPPED = "stopped";
        public static final String STATE_FAILED = "failed";

        static Status notRunning() {
            return new Status(STATE_STOPPED, List.of(), null, null);
        }
    }

    private static final class Running {
        final Process process;
        final Instant startedAt = Instant.now();
        final ConcurrentLinkedDeque<String> lines = new ConcurrentLinkedDeque<>();
        volatile String state = Status.STATE_STARTING;
        volatile String url;

        Running(Process process) {
            this.process = process;
        }

        void append(String line) {
            lines.addLast(line);
            while (lines.size() > MAX_LOG_LINES) {
                lines.pollFirst();
            }
            if (url == null) {
                Matcher matcher = READY_URL.matcher(line);
                if (matcher.find()) {
                    url = matcher.group();
                    state = Status.STATE_RUNNING;
                }
            }
        }
    }

    public boolean isRunning(UUID projectId) {
        Running running = byProject.get(projectId);
        return running != null && running.process.isAlive();
    }

    /**
     * Starts {@code npm install && npm run <devScript>} in {@code
     * destinationRoot}. Shell-wrapped ({@code cmd.exe /c}/{@code sh -c}),
     * not the argument-array/no-shell pattern ADR-043/044 otherwise always
     * use — {@link ProcessBuilder} does not resolve {@code npm}/{@code
     * npm.cmd} through {@code PATHEXT} the way a real shell does, so
     * invoking it directly reliably fails to even launch on Windows. Judged
     * safe here specifically because {@code devScript} is validated to
     * exist as a real {@code package.json} script key by the caller before
     * this is ever reached, never text taken from a request body.
     *
     * @throws IllegalStateException if this project already has a live process — stop it first.
     */
    public void start(UUID projectId, Path destinationRoot, String devScript) throws IOException {
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        String command = "npm install && npm run " + devScript;
        List<String> args = windows ? List.of("cmd.exe", "/c", command) : List.of("sh", "-c", command);
        start(projectId, args, destinationRoot);
    }

    /**
     * The real process-lifecycle logic {@link #start(UUID, Path, String)}
     * delegates to once it has built the real npm command — a package-private
     * seam so tests can exercise the reader thread/log capture/"ready" URL
     * detection/state machine against a small, controlled command instead of
     * a real {@code npm install}, mirroring {@code AxRenewalCodeGenerator}'s
     * own {@code copyTree}/{@code diffAgainstSource} testability seams.
     */
    void start(UUID projectId, List<String> command, Path cwd) throws IOException {
        Running existing = byProject.get(projectId);
        if (existing != null && existing.process.isAlive()) {
            throw new IllegalStateException("a local run is already in progress for this project");
        }

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(cwd.toFile());
        builder.redirectErrorStream(true);
        Process process = builder.start();

        Running running = new Running(process);
        byProject.put(projectId, running);

        Thread reader = new Thread(() -> readUntilExit(projectId, running), "ax-renewal-run-locally-" + projectId);
        reader.setDaemon(true);
        reader.start();
    }

    private void readUntilExit(UUID projectId, Running running) {
        try (BufferedReader in =
                new BufferedReader(new InputStreamReader(running.process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                running.append(line);
            }
        } catch (IOException e) {
            log.warn("run-locally log reader for project {} stopped: {}", projectId, e.getMessage());
        }
        try {
            int exitCode = running.process.waitFor();
            // A concurrent stop() already set this — its own explicit
            // "stopped" must not be overwritten by the exit this same
            // destroy() call caused.
            if (!Status.STATE_STOPPED.equals(running.state)) {
                running.state = exitCode == 0 ? Status.STATE_STOPPED : Status.STATE_FAILED;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public Status status(UUID projectId) {
        Running running = byProject.get(projectId);
        if (running == null) {
            return Status.notRunning();
        }
        return new Status(running.state, List.copyOf(running.lines), running.url, running.startedAt);
    }

    /**
     * Kills the whole process tree, not just the shell wrapper — {@code npm}
     * and the dev server it spawns are children of the {@code cmd}/{@code
     * sh} process {@link #start} launched, and destroying only that parent
     * leaves the thing a person is actually trying to stop still running.
     */
    public void stop(UUID projectId) {
        Running running = byProject.get(projectId);
        if (running == null) {
            return;
        }
        running.state = Status.STATE_STOPPED;
        running.process.descendants().forEach(ProcessHandle::destroy);
        running.process.destroy();
    }

    /** A backend restart during development is frequent and expected; without this, its own child dev servers would keep running, orphaned, on ports nobody remembers starting. */
    @PreDestroy
    void stopEverything() {
        byProject.keySet().forEach(this::stop);
    }
}
