package com.coc.docs.axrenewal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Runs a Generate run's own {@code scripts.test} against a fresh, disposable
 * copy of its staged changes — never the staging directory itself (Approve
 * still needs to read that byte-for-byte later) and never the live
 * destination (PM decision, ADR-060 Phase 2 §Open Items: side effects from a
 * test run must never touch what Run Locally or a person is actually looking
 * at right now).
 *
 * <p>Shell-wrapped ({@code cmd.exe /c}/{@code sh -c}), the same justified
 * exception {@link AxRenewalLocalRunner#start} already documents — {@link
 * ProcessBuilder} does not resolve {@code npm}/{@code npm.cmd} through {@code
 * PATHEXT} the way a real shell does. Safe for the same reason: nothing here
 * is caller-supplied text concatenated into the command line, only the fixed
 * literal {@code npm install && npm test}.
 *
 * <p>// req: ADR-060
 */
@Component
public class AxRenewalTestRunner {

    private static final Logger log = LoggerFactory.getLogger(AxRenewalTestRunner.class);

    /** Long enough for a real install-and-test pass, short enough that a hung suite still surfaces as a failure rather than an indefinite wait. */
    private static final Duration TIMEOUT = Duration.ofMinutes(15);

    private final ObjectMapper json;

    public AxRenewalTestRunner(ObjectMapper json) {
        this.json = json;
    }

    /**
     * @param executed false when nothing was actually run — no staged
     *     changes, no {@code test} script, a copy/spawn failure, or a
     *     timeout — with {@code note} saying which, honestly, rather than
     *     reporting a fabricated pass or fail. True means {@code exitCode}
     *     and {@code output} are the process's own real result, whatever it
     *     was — a nonzero exit here is a legitimate answer ("tests ran, some
     *     failed"), not a reason to report {@code executed=false}.
     */
    public record Result(boolean executed, Integer exitCode, List<String> output, String note) {
        static Result notExecuted(String note) {
            return new Result(false, null, List.of(), note);
        }
    }

    public Result run(String stagingPath) {
        if (stagingPath == null) {
            return Result.notExecuted("this run has no staged changes to test");
        }
        Path staging = Path.of(stagingPath);
        if (!Files.isDirectory(staging)) {
            return Result.notExecuted("this run's staged changes are no longer on disk");
        }
        if (!hasTestScript(staging)) {
            return Result.notExecuted("this project's package.json has no \"test\" script to run");
        }

        Path copy;
        try {
            copy = Files.createTempDirectory("ax-renewal-test-");
            Files.delete(copy); // copyTree requires the target not to exist yet
            AxRenewalCodeGenerator.copyTree(staging, copy);
        } catch (IOException e) {
            return Result.notExecuted("could not prepare a disposable copy to test: " + e.getMessage());
        }
        try {
            return runNpmTest(copy);
        } finally {
            AxRenewalImplementationRunner.deleteQuietly(copy);
        }
    }

    private boolean hasTestScript(Path dir) {
        Path packageJson = dir.resolve("package.json");
        if (!Files.isRegularFile(packageJson)) {
            return false;
        }
        try {
            JsonNode manifest = json.readTree(Files.readString(packageJson, StandardCharsets.UTF_8));
            return manifest.path("scripts").path("test").isTextual();
        } catch (IOException e) {
            return false;
        }
    }

    private Result runNpmTest(Path copy) {
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        String command = "npm install && npm test";
        List<String> args = windows ? List.of("cmd.exe", "/c", command) : List.of("sh", "-c", command);
        return runCommand(args, copy);
    }

    /**
     * The real process-lifecycle logic {@link #runNpmTest} delegates to once
     * it has built the real npm command — a package-private seam so a test
     * can exercise the reader-thread/timeout/exit-code behaviour against a
     * small, portable command instead of a real {@code npm install}, the
     * same substitution {@code AxRenewalLocalRunner}'s own {@code
     * start(UUID, List, Path)} seam already makes.
     */
    Result runCommand(List<String> args, Path cwd) {
        Process process;
        try {
            ProcessBuilder builder = new ProcessBuilder(args);
            builder.directory(cwd.toFile());
            builder.redirectErrorStream(true);
            process = builder.start();
        } catch (IOException e) {
            return Result.notExecuted("could not start the test run: " + e.getMessage());
        }

        // A background reader, not a blocking readAllBytes()/waitFor() pair
        // in sequence -- a process that never closes its stdout (a genuinely
        // hung test runner, not merely a slow one) must not also block the
        // read that would otherwise happen before this method ever reaches
        // its own timeout. Mirrors AxRenewalLocalRunner's own reader thread.
        List<String> lines = Collections.synchronizedList(new ArrayList<>());
        Thread reader = new Thread(() -> {
            try (BufferedReader in = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) {
                    lines.add(line);
                }
            } catch (IOException e) {
                log.warn("test-run output reader stopped: {}", e.getMessage());
            }
        });
        reader.setDaemon(true);
        reader.start();

        try {
            if (!process.waitFor(TIMEOUT.toMinutes(), TimeUnit.MINUTES)) {
                process.descendants().forEach(ProcessHandle::destroy);
                process.destroyForcibly();
                return Result.notExecuted("running tests timed out after " + TIMEOUT.toMinutes() + " minutes");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.notExecuted("interrupted while running tests");
        }
        // Give the reader a moment to drain whatever the process wrote right
        // before exiting -- best-effort, not a second timeout to fail over.
        try {
            reader.join(5_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return new Result(true, process.exitValue(), List.copyOf(lines), null);
    }
}
