package com.coc.docs.axrenewal;

import com.coc.docs.account.AppRole;
import com.coc.docs.audit.AuditAction;
import com.coc.docs.audit.AuditRepository;
import com.coc.docs.auth.CurrentUser;
import com.coc.docs.web.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Business rules for one AX Renewal project: the attach → analyze → plan
 * (four gated sections) → implement → audit workflow (ADR-037).
 *
 * <p><b>Authorisation is enforced exactly once, in {@code SecurityConfig}</b>
 * — this class never re-checks {@code actor.isAtLeast(...)} or which roles
 * {@code actor} holds itself, the same trust {@code AccountService} and
 * {@code ClaudeUsageService} place in the filter chain (ADR-011). The one
 * thing that genuinely cannot live in {@code SecurityConfig} is {@link
 * #startImplementation}'s "all four sections confirmed and a destination is
 * set" precondition, because it depends on row data, not on who is calling —
 * that is a business rule, reported as a 409, not an authorisation decision.
 *
 * <p>// req: ADR-037
 */
@Service
public class AxRenewalService {

    private final AxRenewalProjectRepository projects;
    private final AxRenewalAnalysisRepository analyses;
    private final AxRenewalPlanRepository plan;
    private final AxRenewalDatabaseConnectionRepository dbConnections;
    private final AxRenewalDestinationRepository destinations;
    private final AxRenewalConfirmationRepository confirmations;
    private final AxRenewalImplementationRepository implementations;
    private final AxRenewalCommentRepository comments;
    private final AuditRepository audit;
    private final ObjectMapper json;
    private final LocalClaudeAnalyzer localClaude;
    private final LocalClaudeChat localChat;
    private final AxRenewalProperties properties;
    private final AxRenewalCodeGenerator generator;
    private final AxRenewalImplementationRunner runner;
    private final AxRenewalLocalRunner localRunner;

    public AxRenewalService(
            AxRenewalProjectRepository projects,
            AxRenewalAnalysisRepository analyses,
            AxRenewalPlanRepository plan,
            AxRenewalDatabaseConnectionRepository dbConnections,
            AxRenewalDestinationRepository destinations,
            AxRenewalConfirmationRepository confirmations,
            AxRenewalImplementationRepository implementations,
            AxRenewalCommentRepository comments,
            AuditRepository audit,
            ObjectMapper json,
            LocalClaudeAnalyzer localClaude,
            LocalClaudeChat localChat,
            AxRenewalProperties properties,
            AxRenewalCodeGenerator generator,
            AxRenewalImplementationRunner runner,
            AxRenewalLocalRunner localRunner) {
        this.projects = projects;
        this.analyses = analyses;
        this.plan = plan;
        this.dbConnections = dbConnections;
        this.destinations = destinations;
        this.confirmations = confirmations;
        this.implementations = implementations;
        this.comments = comments;
        this.audit = audit;
        this.json = json;
        this.localClaude = localClaude;
        this.localChat = localChat;
        this.properties = properties;
        this.generator = generator;
        this.runner = runner;
        this.localRunner = localRunner;
    }

    // -----------------------------------------------------------------------
    // Projects
    // -----------------------------------------------------------------------

    public List<AxRenewalProject> list() {
        return projects.list();
    }

    @Transactional
    public AxRenewalProject create(String name, String description, CurrentUser actor) {
        if (name == null || name.isBlank()) {
            throw ApiException.badRequest("a project needs a name");
        }
        AxRenewalProject created = projects.insert(name.trim(), description, actor.id());
        audit.append(
                actor.id(),
                actor.email(),
                AuditAction.AX_RENEWAL_PROJECT_CREATE,
                AuditAction.TARGET_AX_RENEWAL_PROJECT,
                created.id(),
                json.createObjectNode().put("name", created.name()));
        return created;
    }

    /** Everything one project's screen renders, assembled in one call. */
    public AxRenewalProjectDetail detail(UUID projectId) {
        AxRenewalProject project = requireProject(projectId);
        return new AxRenewalProjectDetail(
                project,
                analyses.find(projectId).orElse(null),
                plan.findStackLayers(projectId),
                plan.findArchitecture(projectId).orElse(null),
                plan.findPageMappings(projectId),
                plan.findDbChanges(projectId),
                dbConnections.find(projectId).orElse(null),
                destinations.find(projectId).orElse(null),
                confirmations.findAllForProject(projectId),
                implementations.findLatest(projectId).orElse(null),
                comments.listForProject(projectId));
    }

    private AxRenewalProject requireProject(UUID projectId) {
        return projects.findById(projectId).orElseThrow(() -> ApiException.notFound("project"));
    }

    /**
     * Validates a caller-supplied source path — one team member's own legacy
     * checkout, given for one call to Run analysis, Start implementation, or
     * the assistant chat (ADR-052 revises ADR-051). Null or blank (nobody
     * gave one) returns null, meaning "no source available for this call" —
     * there is no server-side default left to defer to. Anything else must
     * already be a real, readable directory on this backend's own disk, or
     * the request is refused (400) before a subprocess is ever spawned over
     * it.
     */
    private String requireReadableDirectoryOrNull(String sourcePath) {
        if (sourcePath == null || sourcePath.isBlank()) {
            return null;
        }
        Path candidate;
        try {
            candidate = Path.of(sourcePath.trim());
        } catch (InvalidPathException e) {
            throw ApiException.badRequest("that doesn't look like a valid path: " + sourcePath);
        }
        if (!Files.isDirectory(candidate)) {
            throw ApiException.badRequest(
                    "that path isn't a folder this backend can read: " + sourcePath);
        }
        return candidate.toString();
    }

    /**
     * Deletes a project outright (ADR-050). Every row that hangs off it —
     * analysis, plan, confirmations, comments, implementation runs,
     * destination, database connection — cascades away with it at the
     * database level ({@code on delete cascade}, V25). Unlike a comment
     * (ADR-045), there is no soft path here: "delete project" is asked for by
     * that name, and a workspace nobody can even reach through the picker is
     * not the same request as "keep it around, hidden".
     *
     * <p>Refused (409) while an implementation run is still active (running,
     * generating, awaiting_review, applying) or a local dev server is still
     * live for this project — the same "one active thing" shape {@link
     * #startImplementation} already uses, and a business rule rather than an
     * authorisation decision, which is why it lives here and not in {@code
     * SecurityConfig}. Neither is stopped on the caller's behalf: this
     * method does not cancel a run or kill a process by itself, so nothing
     * outside this project's own rows is silently torn down by a click meant
     * only to remove a workspace.
     *
     * <p>Audited before the row is gone, the same order {@code
     * AccountService#delete} uses — {@code target_id} in the entry keeps
     * pointing at a project that no longer exists, which is why {@code
     * detail} also carries its name.
     */
    @Transactional
    public void delete(UUID projectId, CurrentUser actor) {
        AxRenewalProject project = requireProject(projectId);
        if (implementations.findActive(projectId).isPresent()) {
            throw ApiException.conflict("cancel or resolve the active implementation run before deleting this project");
        }
        if (localRunner.isRunning(projectId)) {
            throw ApiException.conflict("stop the local run before deleting this project");
        }

        audit.append(
                actor.id(),
                actor.email(),
                AuditAction.AX_RENEWAL_PROJECT_DELETE,
                AuditAction.TARGET_AX_RENEWAL_PROJECT,
                projectId,
                json.createObjectNode().put("name", project.name()));

        projects.delete(projectId);
    }

    // -----------------------------------------------------------------------
    // Attach
    // -----------------------------------------------------------------------

    /** Serialised size limit for {@code attach_stats} — a defensive cap, not a real-world size (see {@link #attachManifest}). */
    private static final int MAX_ATTACH_STATS_LENGTH = 20_000;

    /**
     * Records a manifest against this project. Text only — file contents, not
     * parsed structure; {@link #runAnalysis} is the step that reads it.
     *
     * <p>{@code filename}/{@code content} are both null when the attached
     * folder had no recognized dependency manifest — that is a fact about the
     * project (e.g. a static site with no {@code package.json}), not a
     * reason to refuse the attach. {@code manifest_attached_at} is set
     * either way, so the project's attach state is never "not attached" just
     * because it has nothing {@link #runAnalysis} can key off of.
     *
     * <p>{@code stats} is the browser's own real computation over the picked
     * folder (file count, size, extensions, lockfile/secrets/build-output
     * checks, largest file) — never fabricated content, so it is trusted and
     * persisted as-is, only capped in size as a defensive limit against an
     * oversized payload.
     */
    @Transactional
    public AxRenewalProject attachManifest(
            UUID projectId, String filename, String content, JsonNode stats, CurrentUser actor) {
        requireProject(projectId);
        if (stats != null && stats.toString().length() > MAX_ATTACH_STATS_LENGTH) {
            throw ApiException.badRequest("attach stats payload is too large");
        }
        projects.attachManifest(projectId, filename, content, stats, actor.id());
        audit.append(
                actor.id(),
                actor.email(),
                AuditAction.AX_RENEWAL_MANIFEST_ATTACH,
                AuditAction.TARGET_AX_RENEWAL_PROJECT,
                projectId,
                json.createObjectNode().put("filename", filename));
        return requireProject(projectId);
    }

    // -----------------------------------------------------------------------
    // Analysis — deterministic, no network call (Phase 1, ADR-037)
    // -----------------------------------------------------------------------

    /**
     * A fixed keyword match against manifest filenames/content, each mapped
     * to the runtime it implies. Deliberately tiny and deterministic — this
     * is not meant to be a real analysis, only an honest, reproducible
     * placeholder until a real model is wired in (Phase 2).
     */
    private static final String[][] RUNTIME_HINTS = {
        {"package.json", "Node.js"},
        {"composer.json", "PHP"},
        {"requirements.txt", "Python"},
        {"pyproject.toml", "Python"},
        {"pom.xml", "Java"},
        {"build.gradle", "Java"},
        {"gemfile", "Ruby"},
        {"go.mod", "Go"},
        {"cargo.toml", "Rust"}
    };

    /**
     * Runs the stub analysis and records the result. No third-party AI call
     * exists yet (ADR-037) — {@code stackDetected}/{@code findings} are
     * derived from simple, disclosed keyword matching against the attached
     * manifest, and {@code findings} always states plainly that no model is
     * connected, the same honest fallback the prototype this replaces used.
     *
     * @param sourcePath one caller's own legacy checkout, typed in for this
     *     one run rather than read from {@code app.ax-renewal.source-path}
     *     (ADR-051) — null/blank defers to that configured default exactly as
     *     before. Validated here, not inside {@link LocalClaudeAnalyzer}: a
     *     path a person just typed deserves an immediate 400 naming what is
     *     wrong, not the same silent fallback a stale <em>configuration</em>
     *     value degrades to. Only validated when {@code useModel} is true —
     *     with it false the override could not affect anything anyway (the
     *     keyword branch never looks at either the override or the
     *     configured default), so stale text left in the box from before
     *     Disconnect was pressed must not turn an otherwise-ordinary
     *     keyword-stub run into a 400. Never persisted — this machine's disk
     *     is the only place it means anything, the same reasoning {@code
     *     app.ax-renewal.source-path} itself was already built on.
     */
    @Transactional
    public AxRenewalAnalysis runAnalysis(UUID projectId, boolean useModel, String sourcePath, CurrentUser actor) {
        AxRenewalProject project = requireProject(projectId);
        String resolvedSourcePath = useModel ? requireReadableDirectoryOrNull(sourcePath) : null;

        // A local Claude Code installation, where one is configured and
        // working, and where the caller has not asked for the keyword
        // analysis instead. Absent on every deployment and on any machine
        // that has not opted in, which is why the keyword branch below stays
        // -- it is the answer for everybody else, not dead code (ADR-043).
        Optional<LocalClaudeAnalyzer.Result> local =
                useModel ? localClaude.analyse(project, resolvedSourcePath) : Optional.empty();
        if (local.isPresent()) {
            return persistLocalAnalysis(projectId, project, local.get(), actor);
        }
        String manifest = (project.manifestFilename() == null ? "" : project.manifestFilename().toLowerCase())
                + " " + (project.manifestContent() == null ? "" : project.manifestContent().toLowerCase());

        ObjectNode stackDetected = json.createObjectNode();
        for (String[] hint : RUNTIME_HINTS) {
            if (manifest.contains(hint[0])) {
                stackDetected.put("runtime", hint[1]);
                break;
            }
        }

        ArrayNode findings = json.createArrayNode();
        findings.add(json.createObjectNode()
                .put("severity", "info")
                .put("what", "No AI model is connected on this deployment yet — this analysis is a "
                        + "keyword-matched placeholder, not a real reading of the attached source.")
                .put("where", "AX Renewal · Phase 1"));

        ArrayNode pagesDetected = json.createArrayNode();

        analyses.upsert(
                projectId,
                stackDetected,
                findings,
                pagesDetected,
                json.createObjectNode(),
                json.createArrayNode(),
                json.createArrayNode(),
                json.createObjectNode(),
                false,
                actor.id());
        if (AxRenewalProject.STATUS_DRAFT.equals(project.status())) {
            projects.updateStatus(projectId, AxRenewalProject.STATUS_ANALYZED);
        }

        audit.append(
                actor.id(),
                actor.email(),
                AuditAction.AX_RENEWAL_ANALYSIS_RUN,
                AuditAction.TARGET_AX_RENEWAL_PROJECT,
                projectId,
                json.createObjectNode().put("modelConnected", false));

        return analyses.find(projectId).orElseThrow();
    }

    /**
     * Stores what a local Claude Code run produced.
     *
     * <p>The audit entry records { modelConnected} true and, separately,
     * whether the model actually read the source or only the stored manifest
     * -- two different claims, and conflating them would let a manifest-only
     * run read later as a full review of the code (ADR-043).
     */
    private AxRenewalAnalysis persistLocalAnalysis(
            UUID projectId, AxRenewalProject project, LocalClaudeAnalyzer.Result result, CurrentUser actor) {
        analyses.upsert(
                projectId,
                result.stackDetected(),
                result.findings(),
                result.pagesDetected(),
                result.indexed(),
                result.runningOnToday(),
                result.fileLayout(),
                result.moduleCoupling(),
                true,
                actor.id());
        if (AxRenewalProject.STATUS_DRAFT.equals(project.status())) {
            projects.updateStatus(projectId, AxRenewalProject.STATUS_ANALYZED);
        }

        audit.append(
                actor.id(),
                actor.email(),
                AuditAction.AX_RENEWAL_ANALYSIS_RUN,
                AuditAction.TARGET_AX_RENEWAL_PROJECT,
                projectId,
                json.createObjectNode()
                        .put("modelConnected", true)
                        .put("engine", "local-claude-code")
                        .put("readSource", result.readSource()));

        return analyses.find(projectId).orElseThrow();
    }

    /**
     * What the workspace should say about the analysis engine.
     *
     * <p>Read by the assistant panel so its banner states the truth rather
     * than a hardcoded "no model connected" -- which was right for every
     * deployment and wrong on a machine that has one (ADR-043).
     *
     * <p>{@code readsSource} is always false now (ADR-052): there is no
     * server-side default left to report on, since Run analysis, Start
     * implementation and chat each take their own source path per call
     * instead of reading one from configuration. Whether one particular
     * call will read source is a fact about what somebody just typed for
     * it, not about this deployment, so it is not something this
     * project-independent status check can answer.
     */
    public AxRenewalEngineStatus engineStatus() {
        if (!properties.localClaudeConfigured()) {
            return new AxRenewalEngineStatus(false, false);
        }
        return new AxRenewalEngineStatus(true, false);
    }

    /**
     *  connected whether a model answers Run analysis at all
     *  readsSource whether it can see the source, or only the stored
     *     manifest -- a real difference the UI must not blur
     */
    public record AxRenewalEngineStatus(boolean connected, boolean readsSource) {}

    /**
     * One turn of free-text conversation with the local model, about one
     * project.
     *
     * <p><b>Writes nothing.</b> No row, no comment, no audit entry — unlike
     * every other method on this class, which is also why it is not {@code
     * @Transactional} and takes no {@code actor}: there is nothing to
     * attribute, because there is nothing kept. The browser holds the
     * conversation and replays it in {@code history} (ADR-046).
     *
     * <p>The project still has to exist. Asking about one nobody attached is
     * a 404, exactly as reading it would be.
     *
     * @param sourcePath one caller's own legacy checkout, given for this one
     *     question rather than read from configuration (ADR-052) — validated
     *     the same way {@link #runAnalysis}'s override is, before {@link
     *     LocalClaudeChat} ever sees it.
     */
    public LocalClaudeChat.Reply chat(UUID projectId, List<LocalClaudeChat.Turn> history, String message, String sourcePath) {
        AxRenewalProject project = requireProject(projectId);
        if (message == null || message.isBlank()) {
            throw ApiException.badRequest("say something to the assistant");
        }
        return localChat.reply(project, history, message.trim(), requireReadableDirectoryOrNull(sourcePath));
    }

    // -----------------------------------------------------------------------
    // Plan — stack move
    // -----------------------------------------------------------------------

    @Transactional
    public List<AxRenewalStackLayer> updateStackMove(
            UUID projectId, List<AxRenewalPlanRepository.StackLayerInput> layers, CurrentUser actor) {
        requireProject(projectId);
        plan.replaceStackLayers(projectId, layers, actor.id());
        boolean cleared = confirmations.clear(projectId, AxRenewalSection.STACK_MOVE) > 0;

        audit.append(
                actor.id(),
                actor.email(),
                AuditAction.AX_RENEWAL_STACK_MOVE_EDIT,
                AuditAction.TARGET_AX_RENEWAL_PROJECT,
                projectId,
                json.createObjectNode().put("layerCount", layers.size()).put("confirmationCleared", cleared));

        return plan.findStackLayers(projectId);
    }

    // -----------------------------------------------------------------------
    // Plan — architecture
    // -----------------------------------------------------------------------

    @Transactional
    public AxRenewalArchitecturePlan updateArchitecture(
            UUID projectId, String summary, JsonNode diagram, CurrentUser actor) {
        requireProject(projectId);
        plan.saveArchitecture(projectId, summary, diagram, actor.id());
        boolean cleared = confirmations.clear(projectId, AxRenewalSection.ARCHITECTURE) > 0;

        audit.append(
                actor.id(),
                actor.email(),
                AuditAction.AX_RENEWAL_ARCHITECTURE_EDIT,
                AuditAction.TARGET_AX_RENEWAL_PROJECT,
                projectId,
                json.createObjectNode().put("confirmationCleared", cleared));

        return plan.findArchitecture(projectId).orElseThrow();
    }

    // -----------------------------------------------------------------------
    // Plan — pages
    // -----------------------------------------------------------------------

    @Transactional
    public List<AxRenewalPageMapping> updatePages(
            UUID projectId, List<AxRenewalPlanRepository.PageMappingInput> mappings, CurrentUser actor) {
        requireProject(projectId);
        plan.replacePageMappings(projectId, mappings, actor.id());
        boolean cleared = confirmations.clear(projectId, AxRenewalSection.PAGES) > 0;

        audit.append(
                actor.id(),
                actor.email(),
                AuditAction.AX_RENEWAL_PAGES_EDIT,
                AuditAction.TARGET_AX_RENEWAL_PROJECT,
                projectId,
                json.createObjectNode().put("pageCount", mappings.size()).put("confirmationCleared", cleared));

        return plan.findPageMappings(projectId);
    }

    // -----------------------------------------------------------------------
    // Plan — database
    // -----------------------------------------------------------------------

    @Transactional
    public List<AxRenewalDbChange> updateDatabasePlan(
            UUID projectId, List<AxRenewalPlanRepository.DbChangeInput> changes, CurrentUser actor) {
        requireProject(projectId);
        plan.replaceDbChanges(projectId, changes, actor.id());
        boolean cleared = confirmations.clear(projectId, AxRenewalSection.DATABASE) > 0;

        audit.append(
                actor.id(),
                actor.email(),
                AuditAction.AX_RENEWAL_DATABASE_PLAN_EDIT,
                AuditAction.TARGET_AX_RENEWAL_PROJECT,
                projectId,
                json.createObjectNode().put("changeCount", changes.size()).put("confirmationCleared", cleared));

        return plan.findDbChanges(projectId);
    }

    /**
     * Records the legacy database's connection metadata — no credential, no
     * live connection opened (ADR-037; see {@code ax_renewal_db_connection}'s
     * own comment). Also clears the Database section's confirmation, the
     * same as any other edit to what that section shows.
     */
    @Transactional
    public AxRenewalDbConnection connectDatabase(
            UUID projectId,
            String engine,
            String host,
            Integer port,
            String databaseName,
            String schemaName,
            CurrentUser actor) {
        requireProject(projectId);
        dbConnections.save(projectId, engine, host, port, databaseName, schemaName, null, actor.id());
        boolean cleared = confirmations.clear(projectId, AxRenewalSection.DATABASE) > 0;

        audit.append(
                actor.id(),
                actor.email(),
                AuditAction.AX_RENEWAL_DATABASE_CONNECT,
                AuditAction.TARGET_AX_RENEWAL_PROJECT,
                projectId,
                json.createObjectNode()
                        .put("engine", engine)
                        .put("host", host)
                        .put("confirmationCleared", cleared));

        return dbConnections.find(projectId).orElseThrow();
    }

    // -----------------------------------------------------------------------
    // Output destination
    // -----------------------------------------------------------------------

    @Transactional
    public AxRenewalDestination setDestination(
            UUID projectId, String kind, String target, String branch, String notes, CurrentUser actor) {
        requireProject(projectId);
        if (target == null || target.isBlank()) {
            throw ApiException.badRequest("a destination needs a target");
        }
        destinations.save(projectId, kind, target.trim(), branch, notes, actor.id());

        audit.append(
                actor.id(),
                actor.email(),
                AuditAction.AX_RENEWAL_DESTINATION_SET,
                AuditAction.TARGET_AX_RENEWAL_PROJECT,
                projectId,
                json.createObjectNode().put("kind", kind).put("target", target));

        return destinations.find(projectId).orElseThrow();
    }

    // -----------------------------------------------------------------------
    // Confirmations
    // -----------------------------------------------------------------------

    /**
     * Confirms one plan section. Reachable only by a Super Admin or an
     * account holding the {@code developer} role (ADR-039) — enforced in
     * {@code SecurityConfig}, not here; this method only records
     * <em>which</em> of the two applied, for the trail.
     */
    @Transactional
    public AxRenewalConfirmation confirmSection(UUID projectId, AxRenewalSection section, CurrentUser actor) {
        requireProject(projectId);
        String via = actor.role() == AppRole.SUPER_ADMIN
                ? AxRenewalConfirmation.VIA_SUPER_ADMIN
                : AxRenewalConfirmation.VIA_DEVELOPER_ROLE;

        confirmations.upsert(projectId, section, actor.id(), via);

        audit.append(
                actor.id(),
                actor.email(),
                AuditAction.AX_RENEWAL_SECTION_CONFIRM,
                AuditAction.TARGET_AX_RENEWAL_PROJECT,
                projectId,
                json.createObjectNode().put("section", section.wire()).put("via", via));

        return confirmations.find(projectId, section).orElseThrow();
    }

    /** Reopens (un-confirms) a section — same authority as confirming it. */
    @Transactional
    public void reopenSection(UUID projectId, AxRenewalSection section, CurrentUser actor) {
        requireProject(projectId);
        boolean cleared = confirmations.clear(projectId, section) > 0;
        if (cleared) {
            audit.append(
                    actor.id(),
                    actor.email(),
                    AuditAction.AX_RENEWAL_SECTION_CONFIRM,
                    AuditAction.TARGET_AX_RENEWAL_PROJECT,
                    projectId,
                    json.createObjectNode().put("section", section.wire()).put("reopened", true));
        }
    }

    // -----------------------------------------------------------------------
    // Implementation
    // -----------------------------------------------------------------------

    /**
     * Starts an implementation run, once every section is confirmed and a
     * destination is set — a business rule, reported as a 409, not an
     * authorisation decision (see the class Javadoc).
     *
     * <p>Three paths now, not two. Rebuild — pressed after a filesystem
     * destination already has one <em>applied</em> run, with the confirmed
     * plan still exactly what produced it (same {@code confirmedPlanHash},
     * ADR-053) — replays that run's exact generated changes for a fresh
     * review cycle rather than asking the model again: a real Generate call
     * is not guaranteed to answer the same way twice, and every other team
     * member's own machine has its own copy of the legacy source at its own
     * path, not necessarily byte-identical to whoever ran it first. Without
     * that (a first build, or the plan changed since), this falls back to
     * ADR-044's original two: without a local model, without a filesystem
     * destination, or without a source path given, the Phase-1 placeholder —
     * completes synchronously, records what was confirmed, and says plainly
     * that no code was generated, unlike the prototype this replaces, which
     * fabricated specific before/after metrics for a demo. With a model, a
     * filesystem destination, and a real source path, a real Generate run in
     * the background, returning immediately at {@code generating} — see
     * {@link #approveImplementation} for what happens once it reaches
     * {@code awaiting_review}, exactly as Replay's own new run also does.
     *
     * @param sourcePath one caller's own legacy checkout to generate from —
     *     never configuration (ADR-052 revises ADR-044). Only checked once
     *     every other precondition for a fresh real generation already holds
     *     (a model configured, a filesystem destination, a concrete
     *     confirmed plan, and Rebuild not already replaying instead):
     *     requiring a path a path-less Rebuild or the placeholder path was
     *     never going to use would refuse a request that did not need it.
     */
    @Transactional
    public AxRenewalImplementationRun startImplementation(UUID projectId, String sourcePath, CurrentUser actor) {
        AxRenewalProject project = requireProject(projectId);

        List<AxRenewalSection> missing = Arrays.stream(AxRenewalSection.values())
                .filter(section -> confirmations.find(projectId, section).isEmpty())
                .toList();
        if (!missing.isEmpty()) {
            throw ApiException.conflict("confirm every plan section first: missing "
                    + missing.stream().map(AxRenewalSection::wire).collect(Collectors.joining(", ")));
        }
        AxRenewalDestination destination = destinations
                .find(projectId)
                .orElseThrow(() -> ApiException.conflict("set a destination before starting implementation"));
        if (implementations.findActive(projectId).isPresent()) {
            throw ApiException.conflict("implementation is already running for this project");
        }

        boolean filesystemDestination = AxRenewalDestination.KIND_FILESYSTEM_PATH.equals(destination.kind());
        String confirmedPlan = buildConfirmedPlanDescription(projectId);
        String confirmedPlanHash = confirmedPlan.isBlank() ? null : sha256Hex(confirmedPlan);

        if (filesystemDestination) {
            Optional<AxRenewalImplementationRun> latest = implementations.findLatest(projectId);
            if (latest.isPresent()
                    && AxRenewalImplementationRun.STATUS_APPLIED.equals(latest.get().status())
                    && confirmedPlanHash != null
                    && confirmedPlanHash.equals(latest.get().confirmedPlanHash())) {
                return replayAppliedRun(projectId, project, latest.get(), actor);
            }
        }

        if (generator.canGenerate() && filesystemDestination) {
            if (confirmedPlan.isBlank()) {
                // Confirming a section is not the same as having decided
                // something in it — Stack move can be confirmed with every
                // layer still pointing nowhere (the seeded suggestion only
                // ever fills in the *old* side; a real target is always a
                // person's own decision), same for an empty Architecture
                // draft, no page mappings, no database changes. Caught here,
                // before a run or a 20-minute CLI call exists to fail later
                // with a vaguer version of the same fact.
                throw ApiException.conflict(
                        "the confirmed plan doesn't describe any concrete change yet — enter at least one real "
                                + "Stack move target, an Architecture description, a page mapping, or a database "
                                + "change before rebuilding");
            }
            String resolvedSourcePath = requireReadableDirectoryOrNull(sourcePath);
            if (resolvedSourcePath == null) {
                throw ApiException.conflict("give this project's legacy source path before starting implementation");
            }
            return beginGeneration(projectId, project, destination, actor, confirmedPlan, confirmedPlanHash, resolvedSourcePath);
        }
        return recordPlaceholderCompletion(projectId, project, destination, actor);
    }

    /**
     * Reuses a prior <em>applied</em> run's exact generated changes for a
     * fresh review cycle, instead of asking the model again (ADR-053): the
     * confirmed plan is byte-for-byte the one that already produced {@code
     * appliedRun}, so there is nothing new to generate — only to re-review
     * and write to <em>this</em> destination, which may be a different
     * machine's folder than whoever approved it the first time (the same
     * per-machine reality {@code AxRenewalLocalRunner}'s own Javadoc already
     * notes for a filesystem destination). Carries {@code appliedRun}'s own
     * {@code confirmedPlanHash} forward unchanged — nothing about the plan
     * changed to produce this, so nothing about the fact it matches should
     * either.
     */
    private AxRenewalImplementationRun replayAppliedRun(
            UUID projectId, AxRenewalProject project, AxRenewalImplementationRun appliedRun, CurrentUser actor) {
        List<AxRenewalGeneratedChange> priorChanges = implementations.findGeneratedChanges(appliedRun.id());
        ObjectNode beforeReport = json.createObjectNode()
                .put("mode", AxRenewalImplementationRun.REPORT_MODE_REPLAY)
                .put("replayedFromRunId", appliedRun.id().toString())
                .put("filesReplayed", priorChanges.size());
        AxRenewalImplementationRun run = implementations.insert(
                projectId,
                actor.id(),
                beforeReport,
                AxRenewalImplementationRun.STATUS_AWAITING_REVIEW,
                appliedRun.confirmedPlanHash());
        implementations.insertGeneratedChanges(
                run.id(),
                priorChanges.stream()
                        .map(c -> new AxRenewalImplementationRepository.GeneratedChangeInput(
                                c.path(), c.changeKind(), c.beforeContent(), c.afterContent()))
                        .toList());

        audit.append(
                actor.id(),
                actor.email(),
                AuditAction.AX_RENEWAL_IMPLEMENTATION_REPLAY,
                AuditAction.TARGET_AX_RENEWAL_PROJECT,
                projectId,
                json.createObjectNode()
                        .put("project", project.name())
                        .put("replayedFromRunId", appliedRun.id().toString())
                        .put("filesReplayed", priorChanges.size()));

        return run;
    }

    /**
     * Hex-encoded SHA-256 — the only thing {@link #startImplementation}
     * needs from the confirmed-plan text: whether it is the same as before,
     * never the text itself back out. Package-private, not private, so a
     * test can compute the same hash a given plan text would produce
     * without re-implementing SHA-256 itself.
     */
    static String sha256Hex(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // Every JDK distribution ships SHA-256 (JLS-mandated); this is
            // unreachable outside a broken installation, and there is no
            // sensible fallback to degrade to.
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private AxRenewalImplementationRun beginGeneration(
            UUID projectId,
            AxRenewalProject project,
            AxRenewalDestination destination,
            CurrentUser actor,
            String confirmedPlan,
            String confirmedPlanHash,
            String sourcePath) {
        ObjectNode beforeReport =
                json.createObjectNode().put("mode", "generate").put("destination", destination.target());
        AxRenewalImplementationRun run = implementations.insert(
                projectId,
                actor.id(),
                beforeReport,
                AxRenewalImplementationRun.STATUS_GENERATING,
                confirmedPlanHash);

        audit.append(
                actor.id(),
                actor.email(),
                AuditAction.AX_RENEWAL_IMPLEMENTATION_START,
                AuditAction.TARGET_AX_RENEWAL_PROJECT,
                projectId,
                json.createObjectNode().put("project", project.name()).put("mode", "generate"));

        // Off the request thread from here — AxRenewalImplementationRunner is
        // a separate bean specifically so @Async actually takes effect (see
        // its own Javadoc on why a method here calling itself would not). It
        // must not start until *this* transaction has actually committed the
        // insert above: this whole method still runs inside startImplementation's
        // @Transactional, so firing the async call here would race the runner's
        // first write (markStaging) against a row that, from its own separate
        // connection, does not exist yet — the write silently affects zero rows
        // rather than failing, leaving staging_path permanently null even
        // though the run goes on to reach awaiting_review normally. Deferring
        // to afterCommit is the only way to guarantee ordering.
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                runner.runGeneration(run.id(), confirmedPlan, sourcePath);
            }
        });
        return run;
    }

    private AxRenewalImplementationRun recordPlaceholderCompletion(
            UUID projectId, AxRenewalProject project, AxRenewalDestination destination, CurrentUser actor) {
        List<AxRenewalStackLayer> stackLayers = plan.findStackLayers(projectId);
        List<AxRenewalPageMapping> pageMappings = plan.findPageMappings(projectId);
        List<AxRenewalDbChange> dbChanges = plan.findDbChanges(projectId);

        ObjectNode beforeReport = json.createObjectNode()
                .put("stackLayers", stackLayers.size())
                .put("pageMappings", pageMappings.size())
                .put("databaseChanges", dbChanges.size())
                .put("destination", destination.target());

        AxRenewalImplementationRun run = implementations.insert(projectId, actor.id(), beforeReport);

        ObjectNode afterReport = beforeReport.deepCopy();
        afterReport.put(
                "note",
                "Plan recorded and signed off. This phase does not generate code automatically — "
                        + "carry out the confirmed plan manually, or connect a real build pipeline in a later phase.");
        implementations.markCompleted(run.id(), AxRenewalImplementationRun.STATUS_COMPLETED, afterReport);
        projects.updateStatus(projectId, AxRenewalProject.STATUS_COMPLETED);

        audit.append(
                actor.id(),
                actor.email(),
                AuditAction.AX_RENEWAL_IMPLEMENTATION_START,
                AuditAction.TARGET_AX_RENEWAL_PROJECT,
                projectId,
                json.createObjectNode().put("project", project.name()));

        return implementations.findLatest(projectId).orElseThrow();
    }

    /**
     * Restates the confirmed Plan as instructions for the local model to
     * carry out — every line traces to a row someone actually entered and
     * confirmed; nothing here is invented on top of what was confirmed.
     */
    private String buildConfirmedPlanDescription(UUID projectId) {
        List<AxRenewalStackLayer> stackLayers = plan.findStackLayers(projectId);
        List<AxRenewalPageMapping> pageMappings = plan.findPageMappings(projectId);
        List<AxRenewalDbChange> dbChanges = plan.findDbChanges(projectId);
        AxRenewalArchitecturePlan architecture = plan.findArchitecture(projectId).orElse(null);

        List<String> decidedLayers = stackLayers.stream()
                .filter(l -> l.newTarget() != null && !l.newTarget().equals(l.oldTarget()))
                .map(l -> "- " + l.layerKey() + ": " + (l.oldTarget() == null ? "current" : l.oldTarget()) + " -> "
                        + l.newTarget())
                .toList();

        // Every section's header is conditional on having something real
        // under it — unlike the version this replaced, which always wrote
        // "Stack move:" even with zero decided layers underneath, handing
        // the model what amounted to an empty plan and getting exactly the
        // honest "nothing to change" answer that deserved back. See
        // startImplementation's own check for the same fact, caught earlier.
        StringBuilder out = new StringBuilder();
        if (!decidedLayers.isEmpty()) {
            out.append("Stack move:\n").append(String.join("\n", decidedLayers)).append('\n');
        }
        if (architecture != null && architecture.summary() != null && !architecture.summary().isBlank()) {
            if (out.length() > 0) {
                out.append('\n');
            }
            out.append("Architecture:\n").append(architecture.summary()).append('\n');
        }
        if (!pageMappings.isEmpty()) {
            if (out.length() > 0) {
                out.append('\n');
            }
            out.append("Page mappings:\n");
            for (AxRenewalPageMapping p : pageMappings) {
                out.append("- ")
                        .append(p.oldPath() == null ? "(new)" : p.oldPath())
                        .append(" -> ")
                        .append(p.newPath() == null ? "(retired)" : p.newPath());
                if (p.notes() != null && !p.notes().isBlank()) {
                    out.append(" — ").append(p.notes());
                }
                out.append('\n');
            }
        }
        if (!dbChanges.isEmpty()) {
            if (out.length() > 0) {
                out.append('\n');
            }
            out.append("Database changes:\n");
            for (AxRenewalDbChange d : dbChanges) {
                out.append("- ").append(d.changeType()).append(" on ").append(d.tableName());
                if (d.rationale() != null && !d.rationale().isBlank()) {
                    out.append(" — ").append(d.rationale());
                }
                out.append('\n');
            }
        }
        return out.toString();
    }

    /**
     * Approves a Generate run's proposed changes and copies them into the
     * real destination — the one place in this whole feature that writes
     * outside this application's own database, and the only thing Approve
     * does; Generate itself never touches the destination (ADR-044).
     */
    @Transactional
    public AxRenewalImplementationRun approveImplementation(UUID projectId, UUID runId, CurrentUser actor) {
        AxRenewalProject project = requireProject(projectId);
        AxRenewalImplementationRun run = requireOwnRun(projectId, runId);
        if (!AxRenewalImplementationRun.STATUS_AWAITING_REVIEW.equals(run.status())) {
            throw ApiException.conflict("this run is not awaiting review");
        }
        AxRenewalDestination destination =
                destinations.find(projectId).orElseThrow(() -> ApiException.conflict("no destination set"));
        if (!AxRenewalDestination.KIND_FILESYSTEM_PATH.equals(destination.kind())) {
            throw ApiException.conflict("only a filesystem destination can be applied automatically");
        }
        if (run.stagingPath() == null) {
            throw ApiException.conflict("this run has no staged changes to apply");
        }

        List<AxRenewalGeneratedChange> changes = implementations.findGeneratedChanges(runId);
        implementations.updateStatus(runId, AxRenewalImplementationRun.STATUS_APPLYING);

        Path destinationRoot = Path.of(destination.target()).toAbsolutePath().normalize();
        Path stagingRoot = Path.of(run.stagingPath());
        try {
            Files.createDirectories(destinationRoot);
            for (AxRenewalGeneratedChange c : changes) {
                // Never trust a generated path to stay inside the destination on its
                // own, even though it always has so far (ADR-011's own habit of not
                // trusting a boundary just because nothing has crossed it yet).
                Path target = destinationRoot.resolve(c.path()).normalize();
                if (!target.startsWith(destinationRoot)) {
                    throw new IOException("refusing to write outside the destination for path " + c.path());
                }
                if (AxRenewalGeneratedChange.KIND_DELETE.equals(c.changeKind())) {
                    Files.deleteIfExists(target);
                } else {
                    Files.createDirectories(target.getParent());
                    // Written from the content this run already captured at
                    // diff time (the same text the review screen showed),
                    // never re-read off the disposable staging copy — that
                    // copy is a plain OS temp directory with no retention
                    // guarantee, and Approve can happen long after Generate
                    // finished or from a different machine than the one that
                    // ran it. The real content was already durably recorded
                    // the moment generation succeeded; there is no reason
                    // Apply should depend on that temp directory still being
                    // there.
                    Files.writeString(target, c.afterContent(), StandardCharsets.UTF_8);
                }
            }
        } catch (IOException e) {
            implementations.markFailed(runId, "could not apply the approved changes: " + e.getMessage());
            throw ApiException.conflict("could not apply the approved changes: " + e.getMessage());
        }

        AxRenewalImplementationRunner.deleteQuietly(stagingRoot);
        implementations.clearStaging(runId);
        ObjectNode afterReport =
                json.createObjectNode().put("filesApplied", changes.size()).put("destination", destination.target());
        implementations.markCompleted(runId, AxRenewalImplementationRun.STATUS_APPLIED, afterReport);
        projects.updateStatus(projectId, AxRenewalProject.STATUS_COMPLETED);

        audit.append(
                actor.id(),
                actor.email(),
                AuditAction.AX_RENEWAL_IMPLEMENTATION_APPROVE,
                AuditAction.TARGET_AX_RENEWAL_PROJECT,
                projectId,
                json.createObjectNode().put("project", project.name()).put("filesApplied", changes.size()));

        return implementations.findLatest(projectId).orElseThrow();
    }

    /** Rejects a Generate run — the staged copy is deleted and the destination is never touched (ADR-044). */
    @Transactional
    public AxRenewalImplementationRun rejectImplementation(UUID projectId, UUID runId, CurrentUser actor) {
        AxRenewalProject project = requireProject(projectId);
        AxRenewalImplementationRun run = requireOwnRun(projectId, runId);
        if (!AxRenewalImplementationRun.STATUS_AWAITING_REVIEW.equals(run.status())) {
            throw ApiException.conflict("this run is not awaiting review");
        }
        if (run.stagingPath() != null) {
            AxRenewalImplementationRunner.deleteQuietly(Path.of(run.stagingPath()));
            implementations.clearStaging(runId);
        }
        implementations.markCompleted(
                runId,
                AxRenewalImplementationRun.STATUS_REJECTED,
                json.createObjectNode().put("note", "Rejected on review — nothing was written to the destination."));

        audit.append(
                actor.id(),
                actor.email(),
                AuditAction.AX_RENEWAL_IMPLEMENTATION_REJECT,
                AuditAction.TARGET_AX_RENEWAL_PROJECT,
                projectId,
                json.createObjectNode().put("project", project.name()));

        return implementations.findLatest(projectId).orElseThrow();
    }

    /**
     * Stops a run that is still {@code generating} (ADR-049) — the actual
     * kill happens in {@link AxRenewalImplementationRunner#cancel}; this only
     * validates the run is in a state where cancelling means anything and
     * records that someone asked. The run's own status flips to {@code
     * cancelled} moments later, once the killed process actually unblocks
     * {@code runGeneration}'s own failure branch — not synchronously here,
     * the same "the caller polls, this does not wait" shape {@code
     * approveImplementation}'s own async sibling steps already have.
     */
    @Transactional
    public AxRenewalImplementationRun cancelImplementation(UUID projectId, UUID runId, CurrentUser actor) {
        AxRenewalProject project = requireProject(projectId);
        AxRenewalImplementationRun run = requireOwnRun(projectId, runId);
        if (!AxRenewalImplementationRun.STATUS_GENERATING.equals(run.status())) {
            throw ApiException.conflict("this run is not currently generating");
        }
        if (!runner.cancel(runId)) {
            throw ApiException.conflict("this run has no active process to cancel");
        }

        audit.append(
                actor.id(),
                actor.email(),
                AuditAction.AX_RENEWAL_IMPLEMENTATION_CANCEL,
                AuditAction.TARGET_AX_RENEWAL_PROJECT,
                projectId,
                json.createObjectNode().put("project", project.name()));

        return requireOwnRun(projectId, runId);
    }

    /** The files a Generate run actually found different, for the review screen. */
    public List<AxRenewalGeneratedChange> findGeneratedChanges(UUID projectId, UUID runId) {
        requireOwnRun(projectId, runId);
        return implementations.findGeneratedChanges(runId);
    }

    private AxRenewalImplementationRun requireOwnRun(UUID projectId, UUID runId) {
        AxRenewalImplementationRun run =
                implementations.findById(runId).orElseThrow(() -> ApiException.notFound("implementation run"));
        if (!run.projectId().equals(projectId)) {
            throw ApiException.notFound("implementation run");
        }
        return run;
    }

    // -----------------------------------------------------------------------
    // Run locally (ADR-048)
    // -----------------------------------------------------------------------

    /**
     * Starts the applied destination as a live local dev server. Requires the
     * latest implementation run to have actually reached {@code applied} —
     * the exact "a real generated build to point at" precondition the Run
     * Locally tab has always been locked behind, satisfiable now that
     * Approve (ADR-044) produces exactly that.
     */
    @Transactional
    public AxRenewalLocalRunner.Status startRunLocally(UUID projectId, CurrentUser actor) {
        AxRenewalProject project = requireProject(projectId);
        AxRenewalDestination destination = requireAppliedDestination(projectId);
        Path destinationRoot = Path.of(destination.target()).toAbsolutePath().normalize();
        String devScript = requireDevScript(destinationRoot);

        try {
            localRunner.start(projectId, destinationRoot, devScript);
        } catch (IllegalStateException e) {
            throw ApiException.conflict(e.getMessage());
        } catch (IOException e) {
            throw ApiException.conflict("could not start the local dev server: " + e.getMessage());
        }

        audit.append(
                actor.id(),
                actor.email(),
                AuditAction.AX_RENEWAL_RUN_LOCALLY_START,
                AuditAction.TARGET_AX_RENEWAL_PROJECT,
                projectId,
                json.createObjectNode().put("project", project.name()).put("destination", destination.target()));

        return localRunner.status(projectId);
    }

    @Transactional
    public AxRenewalLocalRunner.Status stopRunLocally(UUID projectId, CurrentUser actor) {
        AxRenewalProject project = requireProject(projectId);
        localRunner.stop(projectId);

        audit.append(
                actor.id(),
                actor.email(),
                AuditAction.AX_RENEWAL_RUN_LOCALLY_STOP,
                AuditAction.TARGET_AX_RENEWAL_PROJECT,
                projectId,
                json.createObjectNode().put("project", project.name()));

        return localRunner.status(projectId);
    }

    /** A plain read — never audited, matching every other status poll in this feature. */
    public AxRenewalLocalRunner.Status runLocallyStatus(UUID projectId) {
        requireProject(projectId);
        return localRunner.status(projectId);
    }

    private AxRenewalDestination requireAppliedDestination(UUID projectId) {
        AxRenewalImplementationRun run = implementations
                .findLatest(projectId)
                .orElseThrow(() -> ApiException.conflict("no implementation has been applied yet"));
        if (!AxRenewalImplementationRun.STATUS_APPLIED.equals(run.status())) {
            throw ApiException.conflict("the latest implementation run has not been applied yet");
        }
        AxRenewalDestination destination =
                destinations.find(projectId).orElseThrow(() -> ApiException.conflict("no destination set"));
        if (!AxRenewalDestination.KIND_FILESYSTEM_PATH.equals(destination.kind())) {
            throw ApiException.conflict("only a filesystem destination can be run locally");
        }
        return destination;
    }

    /** Reads the real {@code scripts.dev} key out of the destination's own {@code package.json} — never assumed, and never the value, only that it exists. */
    private String requireDevScript(Path destinationRoot) {
        Path packageJson = destinationRoot.resolve("package.json");
        if (!Files.isRegularFile(packageJson)) {
            throw ApiException.conflict("no package.json found in the destination — nothing to run");
        }
        JsonNode manifest;
        try {
            manifest = json.readTree(Files.readString(packageJson, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw ApiException.conflict("could not read the destination's package.json: " + e.getMessage());
        }
        if (!manifest.path("scripts").path("dev").isTextual()) {
            throw ApiException.conflict("this project's package.json has no \"dev\" script to run");
        }
        return "dev";
    }

    // -----------------------------------------------------------------------
    // Comments
    // -----------------------------------------------------------------------

    @Transactional
    public AxRenewalComment addComment(UUID projectId, String body, CurrentUser actor) {
        requireProject(projectId);
        if (body == null || body.isBlank()) {
            throw ApiException.badRequest("a comment needs a body");
        }
        AxRenewalComment created =
                comments.insert(projectId, actor.id(), actor.account().displayName(), body.trim());

        audit.append(
                actor.id(),
                actor.email(),
                AuditAction.AX_RENEWAL_COMMENT_ADD,
                AuditAction.TARGET_AX_RENEWAL_PROJECT,
                projectId,
                json.createObjectNode());

        return created;
    }

    /**
     * Hides one comment from the thread (ADR-045).
     *
     * <p><b>Who may:</b> its author, or a {@code super_admin}. That is the
     * one authorisation decision in this class that {@code SecurityConfig}
     * genuinely cannot make, for the same reason {@link #startImplementation}
     * makes its own — "is this your comment" depends on row data, not on who
     * is calling. The filter chain still does the part it can, holding the
     * route at the {@code user} floor; this adds only the ownership test, and
     * reports a refusal as 403.
     *
     * <p><b>Soft.</b> The row stays and no edit exists anywhere, so a
     * discussion cannot be quietly rewritten after the fact — the comment
     * stops being shown, which is the reversible half of the request.
     * Deleting an already-deleted comment is a 404: it is not in the thread,
     * which is all a caller can observe.
     */
    @Transactional
    public void deleteComment(UUID projectId, UUID commentId, CurrentUser actor) {
        requireProject(projectId);
        AxRenewalComment comment = comments
                .findLive(projectId, commentId)
                .orElseThrow(() -> ApiException.notFound("comment"));

        boolean own = comment.authorId().equals(actor.id());
        if (!own && !actor.isAtLeast(AppRole.SUPER_ADMIN)) {
            throw ApiException.forbidden("only its author or a super admin can remove a comment");
        }

        // False means somebody else won the race in the moment between the
        // read above and this update. Their entry is already in the log;
        // adding a second one for the same removal would misreport it as two.
        if (!comments.softDelete(commentId, actor.id())) {
            throw ApiException.notFound("comment");
        }

        audit.append(
                actor.id(),
                actor.email(),
                AuditAction.AX_RENEWAL_COMMENT_DELETE,
                AuditAction.TARGET_AX_RENEWAL_PROJECT,
                projectId,
                json.createObjectNode()
                        .put("commentId", commentId.toString())
                        .put("ownComment", own));
    }
}
