package com.coc.docs.axrenewal;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * Runs one Generate attempt off the request thread (ADR-044).
 *
 * <p>{@code @Async} only takes effect across a bean boundary — Spring's proxy
 * intercepts a call made <em>into</em> this bean from another one, not a
 * method calling itself. {@code RegistrationNotifier}'s own doc comment
 * already records exactly this pitfall for a different feature ("{@code
 * @Async} on RegistrationNotifier is silently ignored" when called via
 * self-invocation), which is the whole reason this is its own small class
 * rather than a method on {@link AxRenewalService} that would call itself.
 *
 * <p>Also owns the one live subprocess a run in progress has, so {@link
 * #cancel} has something real to reach — the same in-memory-only tracking
 * {@code AxRenewalLocalRunner} uses for a different long-lived process
 * (ADR-048), reused here for Generate (ADR-049). Nothing about a live
 * process is persisted: a database row cannot make a killed JVM's process
 * handle exist again after a restart, so this is deliberately not backed by
 * the database the way everything else in this class is.
 */
@Component
public class AxRenewalImplementationRunner {

    private static final Logger log = LoggerFactory.getLogger(AxRenewalImplementationRunner.class);

    private final AxRenewalCodeGenerator generator;
    private final AxRenewalImplementationRepository implementations;
    /** Where "ready for review" is announced (ADR-079) — {@code AxRenewalRunReadyNotifier} listens. */
    private final ApplicationEventPublisher events;

    /**
     * Every staging directory's name starts with this, directly under the
     * system temp directory — which is also what lets a sweep (ADR-072,
     * ADR-073) check that a path read back from the database really is one of
     * these before deleting it.
     */
    static final String STAGING_PREFIX = "ax-renewal-generate-";

    /**
     * How long a {@code generating} run can genuinely last before it must be
     * dead, wherever it was started (ADR-073): three times the CLI's own hard
     * timeout ({@link AxRenewalCodeGenerator#TIMEOUT}), which leaves room for
     * copying the source in before the process starts and diffing it after.
     * Older than this, no backend's process can still be working on it — the
     * one rule that is safe on a database several developers' backends share.
     */
    static final Duration GENERATING_ORPHANED_AFTER = AxRenewalCodeGenerator.TIMEOUT.multipliedBy(3);

    static final String INTERRUPTED_REASON =
            "interrupted — the backend stopped before this run finished. Nothing was applied; start it again.";

    /** The one live CLI process a {@code generating} run has, if any — registered the instant it starts, removed once {@link AxRenewalCodeGenerator#generate} returns either way. */
    private final Map<UUID, Process> runningProcesses = new ConcurrentHashMap<>();
    /** Runs {@link #cancel} was asked to stop — read once by {@link #runGeneration}'s own failure branch to report "cancelled", not a generic failure, then cleared. */
    private final Set<UUID> cancelledRuns = ConcurrentHashMap.newKeySet();
    /** What each run this backend is generating is doing right now (ADR-082) — held only while it runs. */
    private final AxRenewalGenerationProgress progress = new AxRenewalGenerationProgress();

    public AxRenewalImplementationRunner(
            AxRenewalCodeGenerator generator,
            AxRenewalImplementationRepository implementations,
            ApplicationEventPublisher events) {
        this.generator = generator;
        this.implementations = implementations;
        this.events = events;
    }

    /**
     * Stages a copy of the source, asks the local model to carry out {@code
     * confirmedPlan} against it, and records what actually changed — or
     * records exactly why not, never leaving a run silently stuck at {@code
     * generating}. Runs on a background thread; {@code runId}'s row is the
     * only thing the caller can still observe once this returns.
     *
     * @param sourcePath the folder to copy from — captured from the request
     *     that started this run and carried across the async boundary, since
     *     nothing server-side holds it to be read back later. The legacy
     *     checkout for {@code RUN_KIND_PLAN} (ADR-055); the applied
     *     destination itself for {@code RUN_KIND_ERROR_FIX}/{@code
     *     RUN_KIND_UI_CHANGE}, since those two operate on the renewed
     *     project's own code, not the legacy source (ADR-062 Phase 2).
     */
    @Async
    public void runGeneration(UUID runId, String runKind, String instruction, String sourcePath) {
        // Tracked for exactly as long as this run is being generated here,
        // whatever its outcome (ADR-082).
        progress.begin(runId);
        try {
            generate(runId, runKind, instruction, sourcePath);
        } finally {
            progress.end(runId);
        }
    }

    /**
     * What {@code runId} is doing right now, if this backend is generating
     * it (ADR-082) — empty once it has an outcome, or when another backend
     * sharing the database is the one running it.
     */
    public Optional<AxRenewalGenerationProgress.Snapshot> progress(UUID runId) {
        return progress.snapshot(runId);
    }

    private void generate(UUID runId, String runKind, String instruction, String sourcePath) {
        Path staging;
        try {
            staging = Files.createTempDirectory(STAGING_PREFIX);
        } catch (IOException e) {
            fail(runId, "could not create a staging directory: " + e.getMessage());
            return;
        }
        // Every transition below states the status it expects (ADR-073). If
        // this run was already cancelled or recovered — possibly by another
        // backend sharing the database — it is not ours to carry on with.
        if (!implementations.markStagingIfGenerating(runId, staging.toString())) {
            deleteQuietly(staging);
            log.info("generation run {} was closed before it started; not generating", runId);
            return;
        }

        Optional<AxRenewalCodeGenerator.Result> result;
        try {
            result = generator.generate(
                    runKind,
                    instruction,
                    staging,
                    process -> runningProcesses.put(runId, process),
                    sourcePath,
                    progress.listenerFor(runId));
        } catch (RuntimeException e) {
            log.warn("generation run {} failed unexpectedly", runId, e);
            deleteQuietly(staging);
            implementations.clearStaging(runId);
            fail(runId, "generation failed unexpectedly: " + e.getMessage());
            return;
        } finally {
            runningProcesses.remove(runId);
        }
        // Read and cleared exactly once, regardless of outcome — a run that
        // raced a cancel() against its own natural success must not leave a
        // stale entry behind for a runId nothing will ever ask about again.
        boolean wasCancelled = cancelledRuns.remove(runId);

        if (result.isEmpty()) {
            deleteQuietly(staging);
            implementations.clearStaging(runId);
            if (wasCancelled) {
                implementations.endIfStatus(
                        runId,
                        AxRenewalImplementationRun.STATUS_GENERATING,
                        AxRenewalImplementationRun.STATUS_CANCELLED,
                        null);
            } else {
                fail(
                        runId,
                        "the local model could not be reached, timed out, or the run otherwise failed — see the backend log for the exact reason");
            }
            return;
        }

        List<AxRenewalCodeGenerator.GeneratedFile> files = result.get().files();
        if (files.isEmpty()) {
            deleteQuietly(staging);
            implementations.clearStaging(runId);
            fail(runId, "the model made no file changes it could carry out — nothing to review");
            return;
        }

        implementations.insertGeneratedChanges(
                runId,
                files.stream()
                        .map(f -> new AxRenewalImplementationRepository.GeneratedChangeInput(
                                f.path(), f.changeKind(), f.beforeContent(), f.afterContent()))
                        .toList());
        boolean stillOurs = implementations.transitionIfStatus(
                runId, AxRenewalImplementationRun.STATUS_GENERATING, AxRenewalImplementationRun.STATUS_AWAITING_REVIEW);
        if (!stillOurs) {
            // Closed while this process was still working — by a Cancel with
            // no process to reach, or by startup recovery on some backend.
            // That outcome stands; this result is discarded, not reviewed.
            implementations.deleteGeneratedChanges(runId);
            deleteQuietly(staging);
            log.info("generation run {} was closed while generating; its result was discarded", runId);
            return;
        }
        // Only now is it really awaiting review — after the compare-and-set,
        // never for a result that was discarded (ADR-079). The starter is
        // told too: they have been waiting minutes for this.
        implementations.findById(runId)
                .ifPresent(run -> events.publishEvent(new AxRenewalRunReadyEvent(run.projectId(), runId, null)));
    }

    /** Ends a still-{@code generating} run as failed — and does nothing if it was already closed (ADR-073). */
    private void fail(UUID runId, String reason) {
        implementations.endIfStatus(
                runId, AxRenewalImplementationRun.STATUS_GENERATING, AxRenewalImplementationRun.STATUS_FAILED, reason);
    }

    /**
     * Kills a run's live process, if it still has one (ADR-049). Kills the
     * whole process tree, not just the top-level handle — the CLI's own
     * child processes are not reaped by destroying only the top, exactly
     * {@code AxRenewalLocalRunner#stop}'s own reasoning (ADR-048). Marking
     * {@code runId} as cancelled here, before destroying anything, is what
     * lets {@link #runGeneration}'s own failure branch — which the killed
     * process is about to reach — report "cancelled" rather than a generic
     * failure.
     *
     * @return false if this run has no live process to kill (already
     *     finished, or never had one) — the caller reports that as a
     *     conflict rather than a silent no-op.
     */
    public boolean cancel(UUID runId) {
        Process process = runningProcesses.get(runId);
        if (process == null || !process.isAlive()) {
            return false;
        }
        cancelledRuns.add(runId);
        process.descendants().forEach(ProcessHandle::destroy);
        process.destroyForcibly();
        return true;
    }

    /**
     * Deletes one staging copy named by a path read back from the database —
     * only if it really is one: a directory directly under the system temp
     * directory whose name carries {@link #STAGING_PREFIX} (ADR-072, ADR-073).
     * A value that had been changed in the database must not be able to point
     * this recursive delete at anything else on the machine. A path from
     * another developer's machine simply is not here, and is left alone.
     */
    static void deleteStagingCopy(String stagingPath) {
        if (stagingPath == null) {
            return;
        }
        Path path;
        try {
            path = Path.of(stagingPath).toAbsolutePath().normalize();
        } catch (InvalidPathException e) {
            return;
        }
        Path tempRoot = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize();
        boolean isStagingCopy = tempRoot.equals(path.getParent())
                && path.getFileName().toString().startsWith(STAGING_PREFIX);
        if (isStagingCopy && Files.isDirectory(path)) {
            deleteQuietly(path);
        }
    }

    /** Best-effort cleanup of a disposable staging copy — a leftover temp directory is not worth failing a run over. */
    static void deleteQuietly(Path dir) {
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException e) {
                    log.warn("could not delete {} while cleaning up a staging directory", p);
                }
            });
        } catch (IOException e) {
            log.warn("could not walk {} while cleaning up a staging directory", dir);
        }
    }
}
