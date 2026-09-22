package com.coc.docs.axrenewal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Runs a real analysis by asking a Claude Code installation on <em>this
 * machine</em> to read the project.
 *
 * <h2>Why this is local-only, and always will be</h2>
 *
 * <p>This is not a step towards the deployed site analysing anything. The
 * deployed backend has no {@code claude} binary and no credential, so
 * {@link AxRenewalProperties#localClaudeConfigured()} is false there and this
 * class is never reached — the keyword stub answers, and says so. What makes
 * it work on a developer machine is co-location: the backend, the CLI, the
 * signed-in seat and the source folder are all the same computer. Remove any
 * one of those and this design stops being possible, which is why it cannot
 * become the production answer by configuration alone. A deployed site
 * serving many people through one person's seat is a different thing
 * entirely, and needs the API key in {@link
 * AxRenewalProperties#anthropicApiKey()}.
 *
 * <h2>What the model actually sees</h2>
 *
 * <p>Two levels, because the backend genuinely holds very little. The attach
 * step persists one file — the dependency manifest — plus counted statistics;
 * the source itself never leaves the browser, which is deliberate. So:
 *
 * <ul>
 *   <li><b>Manifest only</b> (no {@code sourcePath}): the model reasons about
 *       the stack, the dependencies and their risks. Real reading of real
 *       content, but it has not seen a line of the code.
 *   <li><b>With {@code sourcePath}</b>: {@code --add-dir} points it at the
 *       folder, and it reads the source the way it would in a terminal.
 * </ul>
 *
 * <p>The distinction is reported back, not hidden, so nothing downstream can
 * claim the source was read when it was not.
 *
 * <h2>Safety</h2>
 *
 * <p>The prompt carries attacker-influenced text — a manifest's contents come
 * from whatever folder somebody attached. It therefore never touches a
 * command line: {@link ProcessBuilder} is given an argument array and no
 * shell, and the prompt goes in over stdin. There is no {@code sh -c}, no
 * string concatenation, and so no command injection to get wrong.
 *
 * <p>// req: ADR-037, ADR-043
 */
@Component
public class LocalClaudeAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(LocalClaudeAnalyzer.class);

    /**
     * Claude Code can think for a while, but an HTTP request must not hang on
     * it indefinitely. Long enough for a real read, short enough that a stuck
     * process surfaces as an error rather than a spinner nobody can explain.
     */
    private static final Duration TIMEOUT = Duration.ofMinutes(3);

    /** Enough manifest to characterise a project; guards against a pathological file. */
    private static final int MAX_MANIFEST_CHARS = 20_000;

    private final AxRenewalProperties properties;
    private final ObjectMapper json;

    public LocalClaudeAnalyzer(AxRenewalProperties properties, ObjectMapper json) {
        this.properties = properties;
        this.json = json;
    }

    /** What one run produced, or {@link Optional#empty()} if it could not run. */
    public record Result(JsonNode stackDetected, JsonNode findings, JsonNode pagesDetected, boolean readSource) {}

    /**
     * Asks the local model to analyse this project.
     *
     * <p>Returns empty rather than throwing on every failure mode — a missing
     * binary, a timeout, a non-zero exit, output that is not the JSON we
     * asked for. Analysis is a convenience on top of a plan somebody is
     * writing by hand; it must not be able to fail the request that triggered
     * it. Each case is logged with enough detail to fix it, because a feature
     * that silently does nothing is worse than one that is plainly off.
     */
    public Optional<Result> analyse(AxRenewalProject project) {
        if (!properties.localClaudeConfigured()) {
            return Optional.empty();
        }

        Path cli = Path.of(properties.claudeCliPath());
        if (!Files.isRegularFile(cli)) {
            log.warn(
                    "app.ax-renewal.claude-cli-path is set to {} but there is no file there; "
                            + "falling back to the keyword stub",
                    cli);
            return Optional.empty();
        }

        boolean readSource = properties.sourcePathConfigured() && Files.isDirectory(Path.of(properties.sourcePath()));
        if (properties.sourcePathConfigured() && !readSource) {
            log.warn(
                    "app.ax-renewal.source-path is set to {} but that is not a directory; "
                            + "analysing the stored manifest only",
                    properties.sourcePath());
        }

        List<String> command = new ArrayList<>(List.of(properties.claudeCliPath(), "--print", "--output-format", "json"));
        if (readSource) {
            command.add("--add-dir");
            command.add(properties.sourcePath());
        }

        try {
            return run(command, prompt(project, readSource), readSource);
        } catch (IOException e) {
            log.warn("could not start {}: {}", properties.claudeCliPath(), e.getMessage());
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    private Optional<Result> run(List<String> command, String prompt, boolean readSource)
            throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(command);
        // stderr kept separate: merged into stdout it would corrupt the JSON
        // this parses, and a warning from the CLI would read as a parse failure.
        builder.redirectErrorStream(false);
        Process process = builder.start();

        // The prompt goes over stdin, never on argv -- see the class Javadoc.
        try (OutputStream stdin = process.getOutputStream()) {
            stdin.write(prompt.getBytes(StandardCharsets.UTF_8));
        }

        String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        if (!process.waitFor(TIMEOUT.toMinutes(), TimeUnit.MINUTES)) {
            process.destroyForcibly();
            log.warn("local Claude analysis timed out after {} minutes", TIMEOUT.toMinutes());
            return Optional.empty();
        }
        if (process.exitValue() != 0) {
            log.warn("local Claude analysis exited {}", process.exitValue());
            return Optional.empty();
        }
        return parse(stdout, readSource);
    }

    /**
     * Reads the CLI's JSON envelope, then the JSON the model was asked to
     * produce inside it.
     *
     * <p>Two layers on purpose: {@code --output-format json} wraps the reply
     * in metadata, and the reply itself is the model's own text. Either can
     * be malformed, and neither is worth failing a request over.
     */
    private Optional<Result> parse(String stdout, boolean readSource) {
        try {
            JsonNode envelope = json.readTree(stdout);
            String body = envelope.path("result").asText("");
            if (body.isBlank()) {
                log.warn("local Claude analysis returned no result field");
                return Optional.empty();
            }
            JsonNode parsed = json.readTree(stripFence(body));
            return Optional.of(new Result(
                    parsed.path("stackDetected"),
                    parsed.path("findings"),
                    parsed.path("pagesDetected"),
                    readSource));
        } catch (IOException e) {
            log.warn("local Claude analysis returned output that is not the JSON it was asked for: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Drops a markdown code fence if the model wrapped its JSON in one.
     * Asking for raw JSON usually works; "usually" is not a contract, and one
     * fence should not cost the whole analysis.
     */
    private static String stripFence(String body) {
        String trimmed = body.trim();
        if (!trimmed.startsWith("```")) {
            return trimmed;
        }
        int firstNewline = trimmed.indexOf('\n');
        int lastFence = trimmed.lastIndexOf("```");
        if (firstNewline < 0 || lastFence <= firstNewline) {
            return trimmed;
        }
        return trimmed.substring(firstNewline + 1, lastFence).trim();
    }

    /**
     * The instruction. Names the output shape exactly, because the caller
     * persists these three fields and a differently-shaped reply is the most
     * likely way this quietly stops working.
     */
    private static String prompt(AxRenewalProject project, boolean readSource) {
        String manifest = project.manifestContent() == null ? "" : project.manifestContent();
        if (manifest.length() > MAX_MANIFEST_CHARS) {
            manifest = manifest.substring(0, MAX_MANIFEST_CHARS);
        }

        return """
                You are analysing a codebase for a planned migration to a newer stack.

                Reply with RAW JSON only. No prose, no markdown fence. Exactly this shape:

                {
                  "stackDetected": { "runtime": "...", "framework": "...", "notes": "..." },
                  "findings": [ { "severity": "info|warn|risk", "what": "...", "where": "..." } ],
                  "pagesDetected": [ { "path": "...", "purpose": "..." } ]
                }

                Rules:
                - findings: what a migration would actually have to deal with. Be specific and
                  brief. Say "none found" as a single info finding rather than inventing risks.
                - pagesDetected: routes or screens you can actually see. Empty array if you
                  cannot see any. Never guess at a page you have not found evidence for.
                - Do not recommend a target stack; that is a decision a person makes later.

                %s

                Dependency manifest (%s):
                %s
                """
                .formatted(
                        readSource
                                ? "The project's source is available to you — read it before answering."
                                : "You have only the manifest below; the source is not available. "
                                        + "Say so in a finding rather than guessing at what the code does.",
                        project.manifestFilename() == null ? "none attached" : project.manifestFilename(),
                        manifest.isBlank() ? "(empty)" : manifest);
    }
}
