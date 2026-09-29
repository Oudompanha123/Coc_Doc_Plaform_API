package com.coc.docs.axrenewal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
 *   <li><b>With {@code sourcePath}</b>: {@code --add-dir} points it at a
 *       disposable, filtered copy of the folder (see {@link
 *       #stageFilteredSource}), and it reads the source the way it would in
 *       a terminal.
 * </ul>
 *
 * <p>The distinction is reported back, not hidden, so nothing downstream can
 * claim the source was read when it was not.
 *
 * <h2>Why a filtered copy, not the folder itself</h2>
 *
 * <p>{@code --add-dir} would just as happily point at {@code sourcePath}
 * directly, and analysis never writes to it either way. But a real checkout
 * commonly carries a {@code node_modules} (or {@code .git}, {@code dist},
 * {@code vendor}, ...) many times the size of the actual source — handing the
 * model raw filesystem access to all of it invites exactly the slow,
 * expensive read this class exists to keep bounded. {@link
 * AxRenewalCodeGenerator#copyTree} already solves this for Generate by
 * staging a filtered copy first; {@link #stageFilteredSource} reuses that
 * same method and exclusion list here, so the two never disagree about what
 * "the project" excludes. The copy is deleted the moment this call is done —
 * unlike Generate's staging copy, nothing downstream ever needs to reread it.
 *
 * <h2>Safety</h2>
 *
 * <p>The prompt carries attacker-influenced text — a manifest's contents come
 * from whatever folder somebody attached. It therefore never touches a
 * command line: {@link ProcessBuilder} is given an argument array and no
 * shell, and the prompt goes in over stdin. There is no {@code sh -c}, no
 * string concatenation, and so no command injection to get wrong.
 *
 * <p>// req: ADR-037, ADR-043, ADR-054, ADR-055
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

    /**
     * What one run produced, or {@link Optional#empty()} if it could not run.
     *
     * @param indexed lines/routes/templates/dependencies/testCoveragePercent —
     *     each null unless the model could point to real evidence for it. Never
     *     a guess: {@link #prompt} tells it explicitly to leave a field null
     *     rather than estimate.
     * @param runningOnToday what the project visibly runs on today (not a
     *     migration target — that stays the Plan section's own, separately
     *     authored data), each entry an honest observation with an optional
     *     caution note (age, lack of maintenance, and so on).
     * @param fileLayout the directories the model actually saw under {@code
     *     source-path}, empty unless {@code readSource} — the browser's own
     *     attach-time scan ({@code AxRenewalProject.attachStats}) covers this
     *     too and is preferred when both exist (it is a full scan, not
     *     whatever subset the model chose to look at), but a project attached
     *     before that scan existed had no such data at all, and re-attaching
     *     is not always convenient just to see this panel — this is the same
     *     folder, read a second way, by whichever of the two is available.
     * @param moduleCoupling which modules import which, empty unless {@code
     *     readSource} — tracing an import graph needs the actual source, not
     *     just the manifest. Backs the Architecture panel's "Module coupling"
     *     diagram; its "System context" diagram is built from data this
     *     class already reports elsewhere ({@code stackDetected}, {@code
     *     fileLayout}, {@code pagesDetected}). "Request lifecycle" (needs a
     *     real timed run) and "Deployment" (needs a person describing how
     *     they actually ship) are not things reading source can ever answer,
     *     so nothing here attempts either.
     * @param recommendedStackMove exactly one entry per {@code
     *     runningOnToday} layer, every time — empty only when {@code
     *     runningOnToday} itself is (ADR-059, revising ADR-043's "do not
     *     recommend a target stack" rule). {@code recommendedTarget} is a
     *     real, specific successor when the model is confident, or {@code
     *     null} when it is not — a null is a complete, honest answer for
     *     that layer, never treated as missing data. Backs Plan → Stack
     *     move's "AI recommends" hint: shown alongside the target field,
     *     never written into it on its own — a person reviews it and can
     *     accept, change to one of {@code alternativeTargets}, or ignore it
     *     entirely and type something else.
     */
    public record Result(
            JsonNode stackDetected,
            JsonNode findings,
            JsonNode pagesDetected,
            JsonNode indexed,
            JsonNode runningOnToday,
            JsonNode fileLayout,
            JsonNode moduleCoupling,
            JsonNode recommendedStackMove,
            boolean readSource) {}

    /**
     * Asks the local model to analyse this project.
     *
     * @param sourcePath one caller's own legacy checkout, given for this one
     *     call — never configuration (ADR-055 revises ADR-043/051). Null or
     *     blank means manifest-only: there is no server-side default left to
     *     fall back to, so this is the only way {@code readSource} is ever
     *     true. Trusted to already be a real directory — {@link
     *     AxRenewalService#runAnalysis} validates that and refuses the
     *     request (400) before this is ever reached, since a typo in
     *     something a person just typed deserves an immediate answer. This
     *     method re-checks it anyway (defence in depth).
     *
     *     <p>Returns empty rather than throwing on every other failure mode —
     *     a missing binary, a timeout, a non-zero exit, output that is not
     *     the JSON we asked for. Analysis is a convenience on top of a plan
     *     somebody is writing by hand; it must not be able to fail the
     *     request that triggered it. Each case is logged with enough detail
     *     to fix it, because a feature that silently does nothing is worse
     *     than one that is plainly off.
     */
    public Optional<Result> analyse(AxRenewalProject project, String sourcePath) {
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

        boolean given = sourcePath != null && !sourcePath.isBlank();
        boolean readSource = given && Files.isDirectory(Path.of(sourcePath));
        if (given && !readSource) {
            log.warn(
                    "the source path given for this run ({}) is not a directory; analysing the stored manifest only",
                    sourcePath);
        }

        Path staging = null;
        if (readSource) {
            Optional<Path> staged = stageFilteredSource(Path.of(sourcePath));
            if (staged.isPresent()) {
                staging = staged.get();
            } else {
                readSource = false;
            }
        }

        List<String> command = new ArrayList<>(List.of(properties.claudeCliPath(), "--print", "--output-format", "json"));
        if (readSource) {
            command.add("--add-dir");
            command.add(staging.toString());
        }

        try {
            return run(command, prompt(project, readSource), readSource);
        } catch (IOException e) {
            log.warn("could not start {}: {}", properties.claudeCliPath(), e.getMessage());
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } finally {
            if (staging != null) {
                AxRenewalImplementationRunner.deleteQuietly(staging);
            }
        }
    }

    /**
     * Stages a disposable, filtered copy of {@code source} — the same
     * dependency/build exclusion {@link AxRenewalCodeGenerator#copyTree}
     * already applies for Generate — for {@code --add-dir} to point at
     * instead of the real folder. Also reused by {@link
     * LocalClaudeChat#reply}, which grants the model {@code Glob}/{@code
     * Grep} over whatever {@code --add-dir} names — a raw {@code
     * node_modules} under those tools is an even more direct way to wedge a
     * chat request than analysis's single read-and-summarise pass. Static
     * and package-private so the exclusion behavior is directly testable
     * without a real CLI (same reasoning as {@link
     * AxRenewalCodeGenerator#copyTree} itself).
     *
     * @return empty on any I/O failure (disk full, permissions, ...), in
     *     which case the caller degrades to manifest-only rather than
     *     failing the request — analysis staying a convenience on top of a
     *     plan somebody writes by hand, same as every other failure mode
     *     this class handles.
     */
    static Optional<Path> stageFilteredSource(Path source) {
        Path staging;
        try {
            staging = Files.createTempDirectory("ax-renewal-analyse-");
        } catch (IOException e) {
            log.warn(
                    "could not create a staging directory for analysis ({}); analysing the stored manifest only",
                    e.getMessage());
            return Optional.empty();
        }
        try {
            AxRenewalCodeGenerator.copyTree(source, staging);
            return Optional.of(staging);
        } catch (IOException e) {
            log.warn(
                    "could not stage a filtered copy of {} for analysis ({}); analysing the stored manifest only",
                    source, e.getMessage());
            AxRenewalImplementationRunner.deleteQuietly(staging);
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
                    parsed.path("indexed"),
                    parsed.path("runningOnToday"),
                    normalizeFileLayout(parsed.path("fileLayout")),
                    normalizeModuleCoupling(parsed.path("moduleCoupling")),
                    normalizeRecommendedStackMove(parsed.path("recommendedStackMove")),
                    readSource));
        } catch (IOException e) {
            log.warn("local Claude analysis returned output that is not the JSON it was asked for: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Defends the module-coupling graph the same way {@link
     * #normalizeFileLayout} defends the directory listing — a well-shaped,
     * empty result is the honest "nothing traced" answer, not a missing key
     * every consumer has to separately guard against.
     */
    private ObjectNode normalizeModuleCoupling(JsonNode raw) {
        ObjectNode result = json.createObjectNode();
        JsonNode modules = raw.path("modules");
        JsonNode edges = raw.path("edges");
        JsonNode hubs = raw.path("hubs");
        JsonNode cycles = raw.path("cycles");
        result.set("modules", modules.isArray() ? modules : json.createArrayNode());
        result.set("edges", edges.isArray() ? edges : json.createArrayNode());
        result.set("hubs", hubs.isArray() ? hubs : json.createArrayNode());
        result.set("cycles", cycles.isArray() ? cycles : json.createArrayNode());
        return result;
    }

    /**
     * Defends the "AI recommends" hint the same way {@link
     * #normalizeModuleCoupling} defends the coupling graph. An entry is
     * dropped entirely only when it lacks {@code layerKey} — with nothing to
     * match it back to a {@code runningOnToday} row, it cannot be shown
     * against anything. {@code recommendedTarget} is deliberately allowed to
     * come out {@code null}: that is the honest "not confident enough to
     * name a successor" answer this prompt explicitly asks for, one entry
     * per layer, every time — not a malformed entry to discard. {@code
     * alternativeTargets} defaults to {@code []} and {@code why} to {@code
     * ""} when the model omitted either.
     *
     * <p>Reads every string field through {@link #textOr}, not a bare {@code
     * asText("")}: Jackson's {@code NullNode.asText()} returns the literal
     * string {@code "null"}, not an empty string, so a model that (correctly,
     * per the prompt) writes JSON {@code null} for "not confident" would
     * otherwise come out the other side as the four-character word "null"
     * shown as if it were a real recommendation.
     */
    private ArrayNode normalizeRecommendedStackMove(JsonNode rawEntries) {
        ArrayNode normalized = json.createArrayNode();
        if (!rawEntries.isArray()) {
            return normalized;
        }
        for (JsonNode entry : rawEntries) {
            String layerKey = textOr(entry.path("layerKey"), "");
            if (layerKey.isBlank()) {
                continue;
            }
            ObjectNode filled = json.createObjectNode();
            filled.put("layerKey", layerKey);
            String recommendedTarget = textOr(entry.path("recommendedTarget"), "");
            if (recommendedTarget.isBlank()) {
                filled.putNull("recommendedTarget");
            } else {
                filled.put("recommendedTarget", recommendedTarget);
            }
            JsonNode alternativeTargets = entry.path("alternativeTargets");
            filled.set("alternativeTargets", alternativeTargets.isArray() ? alternativeTargets : json.createArrayNode());
            filled.put("why", textOr(entry.path("why"), ""));
            normalized.add(filled);
        }
        return normalized;
    }

    /**
     * Fills in the two fields the model was never asked for because it
     * cannot meaningfully know them — {@code fileCount} (it only ever
     * reports a curated subset, not a total it counted) and {@code
     * truncated} (a fixed {@code false}: "truncated" describes a scan that
     * chose to stop early, not a model that was never doing a full listing
     * in the first place). Done here, once, so every consumer of a
     * persisted {@code file_layout} — this frontend included — can rely on
     * the same shape regardless of which of the two sources produced it,
     * rather than each having to guess at what a model-authored entry
     * omitted.
     */
    private ArrayNode normalizeFileLayout(JsonNode rawEntries) {
        ArrayNode normalized = json.createArrayNode();
        if (!rawEntries.isArray()) {
            return normalized;
        }
        for (JsonNode entry : rawEntries) {
            JsonNode files = entry.path("files");
            ObjectNode filled = json.createObjectNode();
            filled.put("dir", entry.path("dir").asText(""));
            filled.put("fileCount", files.isArray() ? files.size() : 0);
            filled.set("files", files.isArray() ? files : json.createArrayNode());
            filled.put("truncated", false);
            JsonNode riskFiles = entry.path("riskFiles");
            filled.set("riskFiles", riskFiles.isArray() ? riskFiles : json.createArrayNode());
            normalized.add(filled);
        }
        return normalized;
    }

    /**
     * A JSON string field, or {@code fallback} for anything that is not
     * actually a JSON string — missing, {@code null}, a number, an object.
     * Deliberately not a bare {@code node.asText(fallback)}: {@link
     * JsonNode#asText()}'s own fallback only applies when the node is
     * missing, and {@code NullNode.asText()} returns the literal string
     * {@code "null"} rather than an empty string, which would otherwise let
     * an explicit JSON {@code null} — the exact, honest "not confident"
     * answer {@link #normalizeRecommendedStackMove} asks the model for —
     * come out the other side reading as the word "null".
     */
    private static String textOr(JsonNode node, String fallback) {
        return node.isTextual() ? node.asText() : fallback;
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
                  "findings": [ { "severity": "info|warn|risk", "what": "...", "where": "...", "fixedBy": "stack_move|database|architecture|decision|null" } ],
                  "pagesDetected": [ { "path": "...", "purpose": "..." } ],
                  "moduleCoupling": {
                    "modules": ["..."],
                    "edges": [[0, 1]],
                    "hubs": [0],
                    "cycles": [[2, 3]]
                  },
                  "indexed": {
                    "lines": number or null,
                    "routes": number or null,
                    "templates": number or null,
                    "dependencies": number or null,
                    "testCoveragePercent": number or null
                  },
                  "runningOnToday": [ { "label": "...", "note": "... or null" } ],
                  "fileLayout": [ { "dir": "...", "files": ["..."], "riskFiles": ["..."] } ],
                  "recommendedStackMove": [ { "layerKey": "...", "recommendedTarget": "... or null", "alternativeTargets": ["..."], "why": "..." } ]
                }

                Rules:
                - findings: what a migration would actually have to deal with. Be specific and
                  brief. Say "none found" as a single info finding rather than inventing risks.
                  "fixedBy" is which part of an already-planned renewal would address this,
                  purely to help someone reading the list, not a target-stack recommendation:
                  "stack_move" for something a newer runtime/language/framework/build tool fixes
                  on its own, "database" for a schema or data-access change, "architecture" for a
                  structural/code-organization change, "decision" for something that needs a
                  person to choose (changes a contract, a behavior, or a cost), and null when it
                  genuinely doesn't fit any of those cleanly — null is the right answer far more
                  often than not; only tag one when you are confident, never to fill the category out.
                - pagesDetected: routes or screens you can actually see. Empty array if you
                  cannot see any. Never guess at a page you have not found evidence for.
                - moduleCoupling: do this before the other sections below, while you still have
                  budget left for it — on a real project this is the single most useful thing
                  you can report, and it is the one most likely to get shortchanged if you leave
                  it for last. Empty "modules"/"edges" only when the source is not available to
                  you at all; otherwise open a representative sample of files — entry points,
                  anything large, anything many others import — and trace their actual
                  import/require statements. A partial graph over 15-25 files you did open beats
                  an empty one because the project has hundreds; never infer an import you have
                  not seen, but do not skip this because tracing every file is impractical — it
                  never was the ask. "modules" is that sample's paths, a couple dozen at most.
                  "edges" is pairs of indices into "modules" meaning the first imports the
                  second. "hubs" lists indices of modules that at least three other reported
                  modules import directly — leave it empty rather than guess if nothing in your
                  sample clears that bar. "cycles" lists index pairs [a, b] where you saw a
                  import b and b import a directly.
                - indexed: every field null unless you can support it with something you
                  actually counted or read. "lines"/"routes"/"templates" need the source
                  available to you — leave them null on a manifest-only run. "dependencies"
                  can come from the manifest alone. "testCoveragePercent" must come from an
                  actual coverage report or badge you found in the project; never estimate it
                  by guessing, and leave it null if you found no such evidence.
                - runningOnToday: what the project visibly runs on today — runtime, framework,
                  language, data access, UI, styling, build and test tooling you can actually
                  see, each with a "note" only when there is something concretely worth
                  flagging about it right now (age, lack of maintenance, a known end-of-life
                  date). null the note otherwise. This describes the present only — put any
                  successor you'd suggest in "recommendedStackMove" below, never here.
                - fileLayout: empty array unless the source is available to you. Otherwise,
                  the real top-level directories you actually looked at (a handful is enough —
                  do not enumerate every file in a large project), each with the filenames you
                  saw in it. "riskFiles" lists only filenames from that same "files" list that
                  you actually found something concerning in (a hardcoded credential, for
                  example) — empty array, not null, when nothing stood out.
                - recommendedStackMove: exactly one entry per "runningOnToday" entry, every time —
                  this array's length must equal "runningOnToday"'s, in the same order. "layerKey"
                  repeats that entry's own "label" text exactly, so it can be matched back to it.
                  This is a hint a person reviews and can freely accept, change, or ignore, never a
                  decision made for them.
                    - Confident about a real, currently-maintained successor: set
                      "recommendedTarget" to it (a well-known LTS/stable release over the newest
                      possible one, unless the project's own evidence points somewhere specific —
                      never invent a version number or product that does not exist), and explain
                      why in "why", grounded in what you actually found (the old one's
                      age/end-of-life/limitation, or a concrete, specific benefit of the new one —
                      never generic filler that would apply to any project). "alternativeTargets" is
                      zero or more other reasonable choices, real technologies only, empty array
                      when you have none worth naming.
                    - Not confident enough to name a specific successor: set "recommendedTarget" to
                      JSON null (not a guessed value, not an empty string) and "alternativeTargets"
                      to an empty array — briefly say in "why" what would be needed to be confident.
                      This is the honest answer far more often than not, never a failure on your
                      part; do not stretch to fill "recommendedTarget" rather than say null.
                  Empty array only when "runningOnToday" itself is empty.

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
