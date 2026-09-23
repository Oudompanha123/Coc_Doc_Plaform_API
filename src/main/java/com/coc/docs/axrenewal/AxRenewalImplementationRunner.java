package com.coc.docs.axrenewal;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
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
 */
@Component
public class AxRenewalImplementationRunner {

    private static final Logger log = LoggerFactory.getLogger(AxRenewalImplementationRunner.class);

    private final AxRenewalCodeGenerator generator;
    private final AxRenewalImplementationRepository implementations;

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
     */
    @Async
    public void runGeneration(UUID runId, String confirmedPlan) {
        Path staging;
        try {
            staging = Files.createTempDirectory("ax-renewal-generate-");
        } catch (IOException e) {
            implementations.markFailed(runId, "could not create a staging directory: " + e.getMessage());
            return;
        }
        implementations.markStaging(runId, staging.toString());

        Optional<AxRenewalCodeGenerator.Result> result;
        try {
            result = generator.generate(confirmedPlan, staging);
        } catch (RuntimeException e) {
            log.warn("generation run {} failed unexpectedly", runId, e);
            deleteQuietly(staging);
            implementations.clearStaging(runId);
            implementations.markFailed(runId, "generation failed unexpectedly: " + e.getMessage());
            return;
        }

        if (result.isEmpty()) {
            deleteQuietly(staging);
            implementations.clearStaging(runId);
            implementations.markFailed(
                    runId,
                    "the local model could not be reached, timed out, or the run otherwise failed — see the backend log for the exact reason");
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
