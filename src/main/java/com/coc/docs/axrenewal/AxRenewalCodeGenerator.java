package com.coc.docs.axrenewal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Asks a local Claude Code installation to actually write code changes for a
 * confirmed Plan, into a disposable copy of the attached source — never the
 * original folder (ADR-044).
 *
 * <h2>Why a staging copy, not the real thing</h2>
 *
 * <p>Two folders this must never touch directly: the attached source
 * ({@code source-path} — the one guarantee this whole product makes, "the
 * old system is never edited") and the destination (writing there is
 * {@link AxRenewalService}'s job, and only after a person approves what's
 * here). So generation runs against a third, disposable copy this class
 * makes for the occasion, and reports what changed by diffing that copy
 * against the untouched original — never the model's own say-so about what
 * it did.
 *
 * <h2>Permissions, not a shell</h2>
 *
 * <p>{@code --permission-mode acceptEdits} auto-approves file edits (the one
 * thing this step exists to do) without also bypassing every other
 * permission check the way {@code --dangerously-skip-permissions} would;
 * {@code --permission-prompts none} means anything else that would need a
 * prompt (there is no terminal to answer one) is denied rather than hung
 * waiting forever; {@code --disallowedTools} rules out shell/network access
 * explicitly, on top of that. {@link ProcessBuilder} still gets an argument
 * array and no shell, and the prompt still goes over stdin — the same
 * defense {@link LocalClaudeAnalyzer} already uses, carried over unchanged.
 *
 * <p>// req: ADR-044, ADR-055
 */
@Component
public class AxRenewalCodeGenerator {

    private static final Logger log = LoggerFactory.getLogger(AxRenewalCodeGenerator.class);

    /**
     * A real transformation can run far longer than analysis's read-only
     * pass — long enough that this must never run on a request thread (see
     * {@link AxRenewalService}'s async wiring). Long enough for real work,
     * short enough that a stuck process still surfaces as a failure.
     */
    private static final Duration TIMEOUT = Duration.ofMinutes(20);

    /** Same directories the browser's own attach-time scan already skips (attachStats.ts) — dependency trees and build output describe nothing about the change being made. */
    private static final Set<String> SKIPPED_DIR_NAMES = Set.of(
            "node_modules", ".git", ".next", "dist", "build", "out", "target", "vendor", ".venv", "__pycache__",
            ".gradle", ".idea", ".vscode");

    /** Caps how much of one file's content is stored/compared — a defensive limit, not a real-world size, matching every other size cap in this package. */
    private static final int MAX_FILE_CHARS = 200_000;
    /** Caps how many changed files one run reports — a runaway or misunderstood instruction should surface as a failure, not a review nobody could read. */
    private static final int MAX_CHANGED_FILES = 300;

    private final AxRenewalProperties properties;
    private final ObjectMapper json;

    public AxRenewalCodeGenerator(AxRenewalProperties properties, ObjectMapper json) {
        this.properties = properties;
        this.json = json;
    }

    /** One file this run actually found different, in both real, full states — never a computed patch format. */
    public record GeneratedFile(String path, String changeKind, String beforeContent, String afterContent) {}

    public record Result(List<GeneratedFile> files) {}

    /**
     * Whether this machine can even attempt generation. Only the CLI itself,
     * since ADR-055: the source folder to copy from is now supplied per call
     * to {@link #generate}, not read from configuration, so it is no longer
     * something this method can check in advance.
     */
    public boolean canGenerate() {
        return properties.localClaudeConfigured();
    }

    /**
     * Runs the generation. {@code stagingDir} must not exist yet — this
     * method creates it as a fresh copy of the source and leaves it in place
     * (with whatever the model changed) for {@link #diffAgainstSource} to
     * read afterward and, if approved, for {@link AxRenewalService} to copy
     * out of. Returns empty on any failure — a missing binary, a timeout, a
     * non-zero exit, an unreadable result, or the process having been killed
     * from outside this call — the caller records why via its own
     * logging/state, exactly {@link LocalClaudeAnalyzer}'s own contract.
     *
     * @param sourcePath the legacy project's folder to copy from — one
     *     caller's own checkout, given for this one run (ADR-055), never
     *     configuration. {@link AxRenewalService#startImplementation}
     *     validates it is a real directory and refuses the request (400)
     *     before this is ever reached; this method re-checks anyway
     *     (defence in depth) and degrades to empty, not an exception, if it
     *     somehow is not.
     * @param onProcessStarted invoked with the real, live {@link Process}
     *     the instant it exists — the same "hand the caller a live handle
     *     immediately" shape {@code AxRenewalLocalRunner#start} already
     *     uses, so a caller can register it somewhere reachable enough to
     *     cancel this run later (ADR-049). Never invoked at all if starting
     *     the process itself fails.
     */
    public Optional<Result> generate(
            String confirmedPlan, Path stagingDir, Consumer<Process> onProcessStarted, String sourcePath) {
        if (!canGenerate()) {
            return Optional.empty();
        }
        Path cli = Path.of(properties.claudeCliPath());
        if (!Files.isRegularFile(cli)) {
            log.warn("app.ax-renewal.claude-cli-path is set to {} but there is no file there; cannot generate", cli);
            return Optional.empty();
        }
        if (sourcePath == null || sourcePath.isBlank() || !Files.isDirectory(Path.of(sourcePath))) {
            log.warn("the source path given for generation ({}) is not a directory; cannot generate", sourcePath);
            return Optional.empty();
        }
        Path source = Path.of(sourcePath);

        try {
            copyTree(source, stagingDir);
        } catch (IOException e) {
            log.warn("could not stage a copy of {} for generation: {}", source, e.getMessage());
            return Optional.empty();
        }

        List<String> command = List.of(
                properties.claudeCliPath(),
                "--print",
                "--output-format",
                "json",
                "--add-dir",
                stagingDir.toString(),
                "--permission-mode",
                "acceptEdits",
                "--permission-prompts",
                "none",
                "--disallowedTools",
                "Bash,WebFetch,WebSearch");

        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.directory(stagingDir.toFile());
            builder.redirectErrorStream(false);
            Process process = builder.start();
            onProcessStarted.accept(process);

            try (OutputStream stdin = process.getOutputStream()) {
                stdin.write(prompt(confirmedPlan).getBytes(StandardCharsets.UTF_8));
            }
            String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

            if (!process.waitFor(TIMEOUT.toMinutes(), TimeUnit.MINUTES)) {
                process.destroyForcibly();
                log.warn("local Claude generation timed out after {} minutes", TIMEOUT.toMinutes());
                return Optional.empty();
            }
            if (process.exitValue() != 0) {
                log.warn("local Claude generation exited {}", process.exitValue());
                return Optional.empty();
            }
            logResult(stdout);
        } catch (IOException e) {
            log.warn("could not start {}: {}", properties.claudeCliPath(), e.getMessage());
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }

        try {
            List<GeneratedFile> files = diffAgainstSource(source, stagingDir);
            return Optional.of(new Result(files));
        } catch (IOException e) {
            log.warn("could not read back the staged changes under {}: {}", stagingDir, e.getMessage());
            return Optional.empty();
        }
    }

    /** Logs the CLI's own summary for whoever debugs a run later — never parsed as structured data, since generation's real answer is the files themselves. */
    private void logResult(String stdout) {
        try {
            JsonNode envelope = json.readTree(stdout);
            log.info("local Claude generation finished: {}", envelope.path("result").asText(""));
        } catch (IOException e) {
            log.warn("local Claude generation produced output that is not the JSON envelope it was asked for");
        }
    }

    private static final Pattern PLACEHOLDER = Pattern.compile("[\\r\\n]+");

    private static String prompt(String confirmedPlan) {
        return """
                You are carrying out an already-decided migration plan against the source
                you can see in your working directory. Make the real file changes the plan
                below describes — create, edit, or delete files as needed. Do not ask
                questions or wait for confirmation; there is nobody to answer. Do not run
                any command, install any dependency, or start any process — only edit files
                directly with your file tools.

                Rules:
                - Only make changes this plan actually calls for. Do not refactor, rename,
                  or "improve" anything the plan does not mention.
                - If a part of the plan is too vague to act on safely, leave those files
                  alone rather than guessing — an incomplete, honest result is better than
                  an invented one.
                - Do not add commentary files, README changes, or a summary document. The
                  only output that matters is the edited source tree itself.

                The confirmed plan:
                %s
                """
                .formatted(PLACEHOLDER.matcher(confirmedPlan).replaceAll("\n"));
    }

    // -------------------------------------------------------------------
    // Filesystem helpers
    // -------------------------------------------------------------------

    /** Copies {@code source} to {@code target} (which must not exist yet), skipping the same dependency/build directories the browser's own attach scan already does. */
    static void copyTree(Path source, Path target) throws IOException {
        Files.createDirectories(target);
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (!dir.equals(source) && SKIPPED_DIR_NAMES.contains(dir.getFileName().toString())) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                Files.createDirectories(target.resolve(source.relativize(dir)));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.copy(file, target.resolve(source.relativize(file)), StandardCopyOption.COPY_ATTRIBUTES);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /**
     * Compares the staged copy against the untouched original, file by file —
     * the real answer to "what changed", regardless of what the model itself
     * reports. A file present in both with identical content is not a
     * change and is left out entirely; a binary or otherwise non-text file
     * that cannot be decoded as UTF-8 is skipped rather than guessed at
     * (logged, not silently dropped).
     */
    static List<GeneratedFile> diffAgainstSource(Path source, Path staged) throws IOException {
        Set<Path> originalRelativePaths = new HashSet<>();
        List<GeneratedFile> changes = new ArrayList<>();

        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (!dir.equals(source) && SKIPPED_DIR_NAMES.contains(dir.getFileName().toString())) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (changes.size() >= MAX_CHANGED_FILES) {
                    return FileVisitResult.TERMINATE;
                }
                Path relative = source.relativize(file);
                originalRelativePaths.add(relative);
                Path stagedFile = staged.resolve(relative);
                String before = readTextOrNull(file);
                if (!Files.exists(stagedFile)) {
                    if (before != null) {
                        changes.add(new GeneratedFile(
                                relative.toString().replace('\\', '/'), AxRenewalGeneratedChange.KIND_DELETE, before, null));
                    }
                    return FileVisitResult.CONTINUE;
                }
                String after = readTextOrNull(stagedFile);
                if (before != null && after != null && !before.equals(after)) {
                    changes.add(new GeneratedFile(
                            relative.toString().replace('\\', '/'), AxRenewalGeneratedChange.KIND_MODIFY, before, after));
                }
                return FileVisitResult.CONTINUE;
            }
        });

        if (changes.size() < MAX_CHANGED_FILES) {
            Files.walkFileTree(staged, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (!dir.equals(staged) && SKIPPED_DIR_NAMES.contains(dir.getFileName().toString())) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    if (changes.size() >= MAX_CHANGED_FILES) {
                        return FileVisitResult.TERMINATE;
                    }
                    Path relative = staged.relativize(file);
                    if (!originalRelativePaths.contains(relative)) {
                        String after = readTextOrNull(file);
                        if (after != null) {
                            changes.add(new GeneratedFile(
                                    relative.toString().replace('\\', '/'), AxRenewalGeneratedChange.KIND_CREATE, null, after));
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        }
        return changes;
    }

    /** Null for anything not readable as UTF-8 text (binary, or another encoding) — skipped from the diff rather than guessed at. Any other read failure is a real problem, not an encoding quirk, so it propagates. */
    private static String readTextOrNull(Path file) throws IOException {
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            return text.length() > MAX_FILE_CHARS ? text.substring(0, MAX_FILE_CHARS) : text;
        } catch (MalformedInputException e) {
            return null;
        }
    }
}
