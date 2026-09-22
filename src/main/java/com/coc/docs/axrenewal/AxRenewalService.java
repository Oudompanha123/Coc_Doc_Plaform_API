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
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    private final AxRenewalProperties properties;

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
            AxRenewalProperties properties) {
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
        this.properties = properties;
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
     */
    @Transactional
    public AxRenewalAnalysis runAnalysis(UUID projectId, boolean useModel, CurrentUser actor) {
        AxRenewalProject project = requireProject(projectId);

        // A local Claude Code installation, where one is configured and
        // working, and where the caller has not asked for the keyword
        // analysis instead. Absent on every deployment and on any machine
        // that has not opted in, which is why the keyword branch below stays
        // -- it is the answer for everybody else, not dead code (ADR-043).
        Optional<LocalClaudeAnalyzer.Result> local =
                useModel ? localClaude.analyse(project) : Optional.empty();
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
     */
    public AxRenewalEngineStatus engineStatus() {
        if (!properties.localClaudeConfigured()) {
            return new AxRenewalEngineStatus(false, false);
        }
        return new AxRenewalEngineStatus(true, properties.sourcePathConfigured());
    }

    /**
     *  connected whether a model answers Run analysis at all
     *  readsSource whether it can see the source, or only the stored
     *     manifest -- a real difference the UI must not blur
     */
    public record AxRenewalEngineStatus(boolean connected, boolean readsSource) {}

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
     * <p><b>Completes synchronously, honestly.</b> Phase 1 generates no code
     * at all (ADR-037): there is no background pipeline to be "running", so
     * pretending one exists by leaving the row in {@code running} would be
     * misleading rather than merely incomplete. The recorded report restates
     * what was confirmed and says plainly that implementing it is a manual
     * or future-automated step — unlike the prototype this replaces, which
     * fabricated specific before/after metrics for a demo. A real tool must
     * not invent numbers it did not measure.
     */
    @Transactional
    public AxRenewalImplementationRun startImplementation(UUID projectId, CurrentUser actor) {
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
}
