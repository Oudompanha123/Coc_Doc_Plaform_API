package com.coc.docs.axrenewal;

import com.coc.docs.web.ApiException;
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
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Free-text conversation with the Claude Code installation on <em>this
 * machine</em>, answered in the AX Assistant panel.
 *
 * <h2>Nothing is stored</h2>
 *
 * <p>A turn is a request and a response, and then it is gone: no table, no
 * comment row, no audit entry. The conversation lives in the browser tab's
 * memory and dies with a refresh — which is the whole point, and why the
 * panel says so rather than letting somebody assume a thread they can come
 * back to. Continuity across turns is therefore the browser's job: it replays
 * what was said in {@code history}, and this class holds no session.
 *
 * <p>Two consequences worth saying out loud, because "not saved" is easy to
 * over-read:
 *
 * <ul>
 *   <li>Claude Code keeps its own transcript under {@code ~/.claude/projects}
 *       on the machine that ran it. Nothing here can or should change that —
 *       it is the CLI's own record, and it is what {@code
 *       tools/claude-usage-reporter} reads to report token usage. "Not
 *       stored" means <em>this application stores nothing</em>.
 *   <li>With no audit entry, there is no record of who asked what. That is
 *       acceptable only while this stays read-only. If chat ever gains the
 *       ability to change a project, it must go back through the audited
 *       command path in {@code AxRenewalService} instead (ADR-046).
 * </ul>
 *
 * <h2>Local-only, for the same reason analysis is</h2>
 *
 * <p>See {@link LocalClaudeAnalyzer} — the backend, the CLI, the signed-in
 * seat and the source are one computer, and no deployment has the binary. The
 * panel only offers chat where {@link AxRenewalProperties#localClaudeConfigured()}
 * is true, and this class refuses rather than pretending otherwise.
 *
 * <h2>Safety</h2>
 *
 * <p>Everything a person types is attacker-influenced text as far as this
 * class is concerned, so it never touches a command line: {@link
 * ProcessBuilder} gets an argument array, no shell, and the prompt goes in
 * over stdin — the same rule {@link LocalClaudeAnalyzer} follows. Two
 * further limits this one adds, because a chat prompt is arbitrary where an
 * analysis prompt is fixed:
 *
 * <ul>
 *   <li>{@code --allowedTools Read Glob Grep} — the model may look at the
 *       source it was pointed at and nothing else. No writes, no shell, no
 *       network, whatever the typed message asks for.
 *   <li>At most {@link #MAX_CONCURRENT} of these run at once. Each one is a
 *       real process on somebody's laptop; a panel that can start an
 *       unbounded number of them is a way to wedge the machine, not a
 *       feature.
 * </ul>
 *
 * <p>// req: ADR-043, ADR-046
 */
@Component
public class LocalClaudeChat {

    private static final Logger log = LoggerFactory.getLogger(LocalClaudeChat.class);

    /**
     * Shorter than {@link LocalClaudeAnalyzer}'s three minutes: an analysis
     * run has a progress panel and somebody who expects to wait, a chat
     * message has a person watching a cursor blink.
     */
    private static final Duration TIMEOUT = Duration.ofMinutes(2);

    /** Turns replayed from the browser. Older ones are dropped, oldest first. */
    static final int MAX_HISTORY_TURNS = 20;

    /** Per message, in either direction — long enough to paste a stack trace. */
    static final int MAX_TURN_CHARS = 4_000;

    private static final int MAX_CONCURRENT = 2;

    private final Semaphore slots = new Semaphore(MAX_CONCURRENT);

    private final AxRenewalProperties properties;
    private final ObjectMapper json;

    public LocalClaudeChat(AxRenewalProperties properties, ObjectMapper json) {
        this.properties = properties;
        this.json = json;
    }

    /** One thing said, by {@code "you"} (the person) or {@code "assistant"}. */
    public record Turn(String role, String text) {

        public boolean fromAssistant() {
            return "assistant".equals(role);
        }
    }

    /**
     * @param text what the model said, as plain text — no JSON envelope, no
     *     structure the panel has to parse.
     * @param readSource whether it could see the source, or only knew what
     *     this application stores. Reported so the panel can say which,
     *     rather than leaving somebody to assume the code was read.
     */
    public record Reply(String text, boolean readSource) {}

    /**
     * Asks the local model one question, in the context of one project.
     *
     * <p>Unlike {@link LocalClaudeAnalyzer#analyse}, this throws rather than
     * falling back. Analysis has a keyword stub to degrade to and a plan
     * somebody is writing by hand regardless; a chat message has no second
     * answer, and a reply that silently never arrives is worse than a line
     * saying why.
     *
     * @throws ApiException 409 when no local CLI is configured, when every
     *     slot is busy, or when the CLI could not produce an answer.
     */
    public Reply reply(AxRenewalProject project, List<Turn> history, String message) {
        if (!properties.localClaudeConfigured()) {
            throw ApiException.conflict(
                    "no local model is configured on this machine — chat needs app.ax-renewal.claude-cli-path");
        }
        Path cli = Path.of(properties.claudeCliPath());
        if (!Files.isRegularFile(cli)) {
            log.warn("app.ax-renewal.claude-cli-path is set to {} but there is no file there", cli);
            throw ApiException.conflict("the configured Claude Code path does not point at a file");
        }

        boolean readSource = properties.sourcePathConfigured() && Files.isDirectory(Path.of(properties.sourcePath()));

        List<String> command = new ArrayList<>(List.of(
                properties.claudeCliPath(),
                "--print",
                "--output-format",
                "json",
                // Read-only by construction, not by asking nicely in the
                // prompt: the message came from a text box. Three separate
                // values, not one comma-joined string — the flag's signature
                // is `--allowedTools <tools...>`, and a single "Read,Glob,Grep"
                // would read as one tool nobody has, which fails by allowing
                // nothing rather than by erroring.
                "--allowedTools",
                "Read",
                "Glob",
                "Grep"));
        if (readSource) {
            command.add("--add-dir");
            command.add(properties.sourcePath());
        }

        // tryAcquire, not acquire: queueing HTTP requests behind a two-minute
        // subprocess turns a busy laptop into a pile of stuck connections.
        // Better to say "busy, try again" straight away.
        if (!slots.tryAcquire()) {
            throw ApiException.conflict("the assistant is already answering — wait for that reply and try again");
        }
        try {
            return new Reply(run(command, prompt(project, history, message, readSource), readSource), readSource);
        } catch (IOException e) {
            log.warn("could not start {}: {}", properties.claudeCliPath(), e.getMessage());
            throw ApiException.conflict("could not start Claude Code on this machine — see the backend log");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw ApiException.conflict("the assistant was interrupted before it answered");
        } finally {
            slots.release();
        }
    }

    private String run(List<String> command, String prompt, boolean readSource)
            throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(command);
        // Run where the source is, when there is source: otherwise the
        // process inherits the backend's own working directory, and "read the
        // project" would quietly mean this repository.
        if (readSource) {
            builder.directory(Path.of(properties.sourcePath()).toFile());
        }
        // Kept separate for the same reason the analyzer keeps it separate —
        // merged into stdout, a CLI warning would corrupt the JSON envelope.
        builder.redirectErrorStream(false);
        Process process = builder.start();

        try (OutputStream stdin = process.getOutputStream()) {
            stdin.write(prompt.getBytes(StandardCharsets.UTF_8));
        }

        String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        if (!process.waitFor(TIMEOUT.toMinutes(), TimeUnit.MINUTES)) {
            process.destroyForcibly();
            log.warn("local Claude chat timed out after {} minutes", TIMEOUT.toMinutes());
            throw ApiException.conflict("the local model did not answer within " + TIMEOUT.toMinutes() + " minutes");
        }
        if (process.exitValue() != 0) {
            log.warn("local Claude chat exited {}", process.exitValue());
            throw ApiException.conflict("Claude Code exited with an error — see the backend log");
        }
        return parse(stdout);
    }

    /**
     * Reads the CLI's JSON envelope. Only one layer here, unlike {@link
     * LocalClaudeAnalyzer#parse}: a chat reply is the model's own prose, and
     * asking it to wrap that in JSON would buy nothing and lose formatting.
     */
    private String parse(String stdout) {
        try {
            JsonNode envelope = json.readTree(stdout);
            String body = envelope.path("result").asText("");
            if (body.isBlank()) {
                log.warn("local Claude chat returned no result field");
                throw ApiException.conflict("the local model returned an empty answer");
            }
            return body.trim();
        } catch (IOException e) {
            log.warn("local Claude chat returned output that is not JSON: {}", e.getMessage());
            throw ApiException.conflict("the local model returned something this could not read");
        }
    }

    /**
     * Builds the single prompt: who it is answering as, what project it is
     * being asked about, what it may see, the conversation so far, and the
     * new message last.
     *
     * <p>Package-private and static so the shaping — the caps, the ordering,
     * the manifest cut-off — can be tested without a Claude Code binary.
     */
    static String prompt(AxRenewalProject project, List<Turn> history, String message, boolean readSource) {
        StringBuilder out = new StringBuilder();
        out.append(
                """
                You are the AX Assistant inside a code-renewal workspace. Someone is asking you \
                about a legacy project they are planning to modernise, from a chat panel in a web \
                page — answer them there, in plain prose. No JSON, no preamble, no restating the \
                question. Be brief and concrete; a few sentences is usually right, and a short \
                list where a list is genuinely clearer.

                Answer only what was asked. You are reading and explaining, not changing anything: \
                you have no ability to edit files, run commands, or alter the project's plan from \
                here, so if you are asked to do one of those, say plainly that this panel cannot \
                and that the workspace's own buttons are where that happens.

                Never invent detail about the project. If you have not seen something, say you \
                have not seen it — that answer is useful, and a confident guess is not.

                """);

        out.append("Project: ").append(nullSafe(project.name(), "(unnamed)")).append('\n');
        if (project.description() != null && !project.description().isBlank()) {
            out.append("Description: ").append(clamp(project.description(), 500)).append('\n');
        }
        out.append("Workflow status: ").append(nullSafe(project.status(), "unknown")).append('\n');
        out.append("Dependency manifest on file: ")
                .append(nullSafe(project.manifestFilename(), "none attached"))
                .append('\n');
        out.append(
                readSource
                        ? "The project's source is available to you — read it before answering.\n"
                        : "You have not been given the source, only what this workspace stores about "
                                + "the project. Say so rather than guessing at what the code does.\n");

        String manifest = clamp(nullSafe(project.manifestContent(), ""), 8_000);
        if (!manifest.isBlank()) {
            out.append("\nDependency manifest:\n").append(manifest).append('\n');
        }

        List<Turn> recent = recent(history);
        if (!recent.isEmpty()) {
            out.append("\nThe conversation so far, oldest first:\n");
            for (Turn turn : recent) {
                out.append(turn.fromAssistant() ? "Assistant: " : "Them: ")
                        .append(clamp(nullSafe(turn.text(), ""), MAX_TURN_CHARS))
                        .append('\n');
            }
        }

        out.append("\nTheir new message:\n").append(clamp(message, MAX_TURN_CHARS)).append('\n');
        return out.toString();
    }

    /** The tail of the conversation — the oldest turns fall off, not the newest. */
    private static List<Turn> recent(List<Turn> history) {
        if (history == null || history.isEmpty()) {
            return List.of();
        }
        return history.size() <= MAX_HISTORY_TURNS
                ? List.copyOf(history)
                : List.copyOf(history.subList(history.size() - MAX_HISTORY_TURNS, history.size()));
    }

    private static String nullSafe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String clamp(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max) + "\n… (truncated)";
    }
}
