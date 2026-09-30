package com.coc.docs.axrenewal;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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

    /** The one live CLI process a {@code generating} run has, if any — registered the instant it starts, removed once {@link AxRenewalCodeGenerator#generate} returns either way. */
    /**
     * Every staging directory's name starts with this, directly under the
     * system temp directory — which is also what lets a sweep (ADR-072) check
     * that a path read back from the database really is one of these before
     * deleting it.
     */
    static final String STAGING_PREFIX = "ax-renewal-generate-";

    private final Map<UUID, Process> runningProcesses = new ConcurrentHashMap<>();
    /** Runs {@link #cancel} was asked to stop — read once by {@link #runGeneration}'s own failure branch to report "cancelled", not a generic failure, then cleared. */
    private final Set<UUID> cancelledRuns = ConcurrentHashMap.newKeySet();

    public AxRenewalImplementationRunner(
            AxRenewalCodeGenerator generator, AxRenewalImplementationRepository implementations) {
        this.generator = generator;
        this.implementations = implementations;
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
        Path staging;
        try {
            staging = Files.createTempDirectory(STAGING_PREFIX);
        } catch (IOException e) {
            implementations.markFailed(runId, "could not create a staging directory: " + e.getMessage());
            return;
        }
        implementations.markStaging(runId, staging.toString());

        Optional<AxRenewalCodeGenerator.Result> result;
        try {
            result = generator.generate(
                    runKind, instruction, staging, process -> runningProcesses.put(runId, process), sourcePath);
        } catch (RuntimeException e) {
            log.warn("generation run {} failed unexpectedly", runId, e);
            deleteQuietly(staging);
            implementations.clearStaging(runId);
            implementations.markFailed(runId, "generation failed unexpectedly: " + e.getMessage());
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
                implementations.markCancelled(runId);
            } else {
                implementations.markFailed(
                        runId,
                        "the local model could not be reached, timed out, or the run otherwise failed — see the backend log for the exact reason");
            }
            return;
        }

        List<AxRenewalCodeGenerator.GeneratedFile> files = result.get().files();
        if (files.isEmpty()) {
            deleteQuietly(staging);
            implementations.clearStaging(runId);
            implementations.markFailed(
                    runId, "the model made no file changes it could carry out — nothing to review");
            return;
        }

        implementations.insertGeneratedChanges(
                runId,
                files.stream()
                        .map(f -> new AxRenewalImplementationRepository.GeneratedChangeInput(
                                f.path(), f.changeKind(), f.beforeContent(), f.afterContent()))
                        .toList());
        implementations.updateStatus(runId, AxRenewalImplementationRun.STATUS_AWAITING_REVIEW);
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
