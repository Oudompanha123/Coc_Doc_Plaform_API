package com.coc.docs.axrenewal;

import jakarta.annotation.PreDestroy;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.TimeUnit;
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
 * <p><b>Ports (ADR-083).</b> A dev server picks the first free port from its
 * default (3000 for Next.js), so a rerun only gets its old port back if the
 * previous server is really gone. Two things used to leave it running:
 * {@link #stop} returned before the process tree had exited, and a backend
 * that stopped without a graceful shutdown left its dev servers behind where
 * its successor could not see them. Stop now waits for the whole tree, and
 * each run's root process is recorded in a small file in the temp directory,
 * so the next Start for that project first stops whatever an earlier backend
 * left running. The port itself is never pinned: an explicit port makes a
 * dev server fail when it is busy instead of moving on.
 *
 * <p>// req: ADR-048, ADR-083
 */
@Component
public class AxRenewalLocalRunner {

    private static final Logger log = LoggerFactory.getLogger(AxRenewalLocalRunner.class);

    /** A rolling window, not a transcript — long enough to show what happened, short enough that a long-running dev server cannot grow this without bound. */
    private static final int MAX_LOG_LINES = 500;

    /** Matches a dev server's own "ready" announcement (Next.js, Vite, CRA all print exactly this shape) — the URL shown is read off this, never assumed from a hardcoded port. */
    private static final Pattern READY_URL = Pattern.compile("https?://(?:localhost|127\\.0\\.0\\.1):\\d+");

    /**
     * A dev server rendering an unhandled error for one request (a Next.js
     * page throwing, e.g.) does not exit the process — it catches the error,
     * answers 500, and keeps serving everything else. That leaves {@link
     * Status#state} at {@link Status#STATE_RUNNING} (still up) or {@link
     * Status#STATE_STOPPED} (a person pressed Stop after seeing it) — never
     * {@link Status#STATE_FAILED}, which this class only ever sets on a real
     * nonzero process exit. {@link #hasRuntimeErrorEvidence} is the real
     * signal the AI Error Assistant (ADR-062) actually needs: a JS error
     * headline ({@code TypeError:}/{@code ReferenceError:}/…) or the dev
     * server's own request-log line reporting a 5xx — two disclosed facts
     * read off real output, not a guess, mirrored exactly by {@code
     * runLocallyGuidance.ts}'s own {@code hasRuntimeErrorEvidence} on the
     * frontend.
     */
    private static final Pattern JS_ERROR_HEADLINE = Pattern.compile("\\b\\w*Error:\\s");

    private static final Pattern SERVER_ERROR_RESPONSE =
            Pattern.compile("\\b(?:GET|POST|PUT|PATCH|DELETE)\\s\\S+\\s5\\d\\d\\b");

    public static boolean hasRuntimeErrorEvidence(List<String> log) {
        for (String line : log) {
            if (JS_ERROR_HEADLINE.matcher(line).find() || SERVER_ERROR_RESPONSE.matcher(line).find()) {
                return true;
            }
        }
        return false;
    }

    private final Map<UUID, Running> byProject = new ConcurrentHashMap<>();

    /** How long {@link #stop} waits for a process tree to exit before giving up on it (ADR-083). */
    static final Duration STOP_WAIT = Duration.ofSeconds(10);

    /**
     * Where each project's live run's root process is recorded — its pid and
     * start time, nothing else — so a backend that restarts can find a dev
     * server its predecessor left running (ADR-083).
     */
    private final Path pidDir;

    public AxRenewalLocalRunner() {
        this(Path.of(System.getProperty("java.io.tmpdir"), "ax-renewal-run-locally"));
    }

    /** With its own pid directory — so tests never touch this machine's real one. */
    AxRenewalLocalRunner(Path pidDir) {
        this.pidDir = pidDir;
    }

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
        // A dev server an earlier backend left running still holds its port;
        // without this the new one would quietly take the next (ADR-083).
        stopLeftover(projectId);

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(cwd.toFile());
        builder.redirectErrorStream(true);
        Process process = builder.start();

        Running running = new Running(process);
        byProject.put(projectId, running);
        recordPid(projectId, process.toHandle());

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
                // It ended by itself: nothing left to find later. A stopped
                // run's record is stop()'s to remove, not this thread's.
                deletePid(projectId, running.process.pid());
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
     *
     * <p>Returns only once the tree has exited, or after {@link #STOP_WAIT}
     * (ADR-083): until then the dev server still holds its port, and a Start
     * pressed straight after would move to the next one.
     */
    public void stop(UUID projectId) {
        Running running = byProject.get(projectId);
        if (running == null) {
            return;
        }
        running.state = Status.STATE_STOPPED;
        killTree(running.process.toHandle());
        deletePid(projectId, running.process.pid());
    }

    /**
     * Kills {@code root} and everything under it, parents before children,
     * and waits for all of it to exit (ADR-083).
     *
     * <p>Each round reads the tree before killing it: a process's parent can
     * no longer be traced once that parent is gone. Parents go first so
     * nothing still alive can start a replacement for a child just killed —
     * {@code next dev}, for one, supervises and can restart its own server.
     * A process caught mid-spawn can still create a child the snapshot
     * missed, so once the round has exited the children of everything killed
     * are read again, and any newcomer is killed in a further round.
     */
    static void killTree(ProcessHandle root) {
        long deadline = System.nanoTime() + STOP_WAIT.toNanos();
        Map<Long, ProcessHandle> killed = new HashMap<>();
        List<ProcessHandle> round = new ArrayList<>();
        round.add(root);
        round.addAll(root.descendants().toList());
        for (int attempt = 0; attempt < 5 && !round.isEmpty(); attempt++) {
            Map<Long, Integer> depth = new HashMap<>();
            for (ProcessHandle h : round) {
                depth.put(h.pid(), depthBelow(h, root));
            }
            round.sort(Comparator.comparingInt(h -> depth.getOrDefault(h.pid(), Integer.MAX_VALUE)));
            round.forEach(ProcessHandle::destroyForcibly);
            round.forEach(h -> killed.put(h.pid(), h));
            if (!awaitExit(round, deadline)) {
                break;
            }
            round = killed.values().stream()
                    .flatMap(ProcessHandle::descendants)
                    .filter(ProcessHandle::isAlive)
                    .filter(h -> !killed.containsKey(h.pid()))
                    .distinct()
                    .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        }
        List<Long> survivors = killed.values().stream().filter(ProcessHandle::isAlive).map(ProcessHandle::pid).toList();
        if (!survivors.isEmpty()) {
            log.warn("run-locally: processes {} were still alive {}s after being stopped", survivors, STOP_WAIT.toSeconds());
        }
    }

    /** Waits for every one of {@code handles} to exit, until {@code deadline}; false if interrupted or out of time. */
    private static boolean awaitExit(List<ProcessHandle> handles, long deadline) {
        for (ProcessHandle h : handles) {
            long left = deadline - System.nanoTime();
            if (left <= 0) {
                return false;
            }
            try {
                h.onExit().get(left, TimeUnit.NANOSECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            } catch (Exception e) {
                return false;
            }
        }
        return true;
    }

    /** How many parents up {@code h} is from {@code root} — 0 for the root itself. */
    private static int depthBelow(ProcessHandle h, ProcessHandle root) {
        int d = 0;
        Optional<ProcessHandle> cursor = Optional.of(h);
        while (cursor.isPresent() && cursor.get().pid() != root.pid() && d < 64) {
            cursor = cursor.get().parent();
            d++;
        }
        return d;
    }

    // --- The pid file (ADR-083): finding what an earlier backend left running ---

    private Path pidFile(UUID projectId) {
        return pidDir.resolve(projectId + ".pid");
    }

    /** Records {@code root} as {@code projectId}'s live run, as "pid startEpochMillis". */
    private void recordPid(UUID projectId, ProcessHandle root) {
        try {
            Files.createDirectories(pidDir);
            long started = root.info().startInstant().map(Instant::toEpochMilli).orElse(0L);
            Files.writeString(pidFile(projectId), root.pid() + " " + started, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("run-locally: could not record the process for project {}: {}", projectId, e.getMessage());
        }
    }

    /** Deletes {@code projectId}'s record, if it is still {@code pid}'s. */
    private void deletePid(UUID projectId, long pid) {
        Path file = pidFile(projectId);
        try {
            if (Files.exists(file) && Files.readString(file, StandardCharsets.UTF_8).trim().startsWith(pid + " ")) {
                Files.delete(file);
            }
        } catch (IOException e) {
            log.debug("run-locally: could not remove the process record for project {}: {}", projectId, e.getMessage());
        }
    }

    /**
     * Stops the dev server an earlier backend recorded for {@code projectId}
     * and left running, if there is one (ADR-083). Only the exact process it
     * recorded: a pid that now belongs to something else — a different start
     * time — is never touched.
     */
    void stopLeftover(UUID projectId) {
        Path file = pidFile(projectId);
        if (!Files.exists(file)) {
            return;
        }
        try {
            String[] parts = Files.readString(file, StandardCharsets.UTF_8).trim().split(" ");
            long pid = Long.parseLong(parts[0]);
            long started = Long.parseLong(parts[1]);
            ProcessHandle.of(pid)
                    .filter(ProcessHandle::isAlive)
                    .filter(h -> started != 0
                            && h.info().startInstant().map(Instant::toEpochMilli).orElse(-1L) == started)
                    .ifPresent(h -> {
                        log.info(
                                "run-locally: stopping the dev server an earlier backend left running for project {} (pid {})",
                                projectId,
                                pid);
                        killTree(h);
                    });
        } catch (IOException | RuntimeException e) {
            log.warn("run-locally: could not read the process record for project {}: {}", projectId, e.getMessage());
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.debug("run-locally: could not remove the process record for project {}: {}", projectId, e.getMessage());
        }
    }

    /**
     * Drops everything held in memory for a project whose process has already
     * ended — its last state and its captured log lines — for a project that
     * no longer exists (ADR-072). Without this, a stopped or failed run's
     * output outlived the project it belonged to until the next restart.
     *
     * <p>Deliberately leaves a still-live process alone: deleting a project
     * is refused while one runs, so reaching here with one alive would be a
     * bug elsewhere, and forgetting it would orphan a process nobody could
     * stop any more.
     */
    public void forget(UUID projectId) {
        byProject.computeIfPresent(projectId, (id, running) -> running.process.isAlive() ? running : null);
    }

    /** A backend restart during development is frequent and expected; without this, its own child dev servers would keep running, orphaned, on ports nobody remembers starting. */
    @PreDestroy
    void stopEverything() {
        byProject.keySet().forEach(this::stop);
    }
}
