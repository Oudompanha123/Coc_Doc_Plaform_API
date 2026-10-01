package com.coc.docs.web;

import com.coc.docs.axrenewal.AxRenewalPlanRepository;
import com.coc.docs.axrenewal.AxRenewalSection;
import com.coc.docs.axrenewal.AxRenewalService;
import com.coc.docs.axrenewal.LocalClaudeChat;
import com.coc.docs.auth.CurrentUser;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AX Renewal: the attach → analyze → plan → implement workflow (ADR-037).
 *
 * <p>Not an {@code Admin}-prefixed controller: unlike the account-authority
 * screens, this surface is reachable by a plain {@code user} who holds the
 * {@code developer} capability, matching this codebase's existing split
 * between {@code Admin<Feature>Controller} (account-authority surfaces) and a
 * plain {@code <Feature>Controller}. {@code SecurityConfig} — not this class
 * — decides exactly who may reach each route; nothing here re-checks a role
 * or a capability.
 *
 * <p>// req: ADR-037
 */
@RestController
@RequestMapping("/api/v1/ax-renewal")
public class AxRenewalController {

    private final AxRenewalService projects;

    public AxRenewalController(AxRenewalService projects) {
        this.projects = projects;
    }

    /** Whether Run analysis reaches a real model on this deployment (ADR-043). */
    @GetMapping("/engine")
    ApiDtos.AxRenewalEngineResponse engine() {
        var status = projects.engineStatus();
        return new ApiDtos.AxRenewalEngineResponse(status.connected(), status.readsSource());
    }

    /**
     * The projects the caller may see (ADR-070) — only those they are a member
     * of, or every project for a super admin — each flagged with whether the
     * caller is a member, which only ever differs for a super admin.
     */
    @GetMapping("/projects")
    List<ApiDtos.AxRenewalProjectResponse> list(@AuthenticationPrincipal CurrentUser actor) {
        var mine = projects.memberProjectIds(actor);
        return projects.list(actor).stream()
                .map(p -> ApiDtos.AxRenewalProjectResponse.of(p, mine.contains(p.id())))
                .toList();
    }

    @PostMapping("/projects")
    ResponseEntity<ApiDtos.AxRenewalProjectResponse> create(
            @Valid @RequestBody ApiDtos.CreateAxRenewalProjectRequest request,
            @AuthenticationPrincipal CurrentUser actor) {
        var created = projects.create(request.name(), request.description(), actor);
        return ResponseEntity.created(URI.create("/api/v1/ax-renewal/projects/" + created.id()))
                .body(ApiDtos.AxRenewalProjectResponse.of(created, true));
    }

    @GetMapping("/projects/{projectId}")
    ApiDtos.AxRenewalProjectDetailResponse detail(
            @PathVariable UUID projectId, @AuthenticationPrincipal CurrentUser actor) {
        return ApiDtos.AxRenewalProjectDetailResponse.of(projects.detail(projectId, actor), actor.id());
    }

    /**
     * Adds a member by email (ADR-069) and returns the project's members
     * after the change. Admin floor in {@code SecurityConfig}; that the caller
     * is on this project themselves is {@code AxRenewalService}'s check,
     * since it is row data.
     */
    @PostMapping("/projects/{projectId}/members")
    List<ApiDtos.AxRenewalMemberResponse> addMember(
            @PathVariable UUID projectId,
            @Valid @RequestBody ApiDtos.AddAxRenewalMemberRequest request,
            @AuthenticationPrincipal CurrentUser actor) {
        return projects.addMember(projectId, request.email(), actor).stream()
                .map(ApiDtos.AxRenewalMemberResponse::of)
                .toList();
    }

    @DeleteMapping("/projects/{projectId}/members/{accountId}")
    ResponseEntity<Void> removeMember(
            @PathVariable UUID projectId, @PathVariable UUID accountId, @AuthenticationPrincipal CurrentUser actor) {
        projects.removeMember(projectId, accountId, actor);
        return ResponseEntity.noContent().build();
    }

    /** Renames a project and sets its description (ADR-076). */
    @PatchMapping("/projects/{projectId}")
    ApiDtos.AxRenewalProjectResponse updateDetails(
            @PathVariable UUID projectId,
            @Valid @RequestBody ApiDtos.UpdateAxRenewalProjectRequest request,
            @AuthenticationPrincipal CurrentUser actor) {
        var updated = projects.updateDetails(projectId, request.name(), request.description(), actor);
        return ApiDtos.AxRenewalProjectResponse.of(updated, projects.isMember(projectId, actor));
    }

    /** Deletes a project outright (ADR-051). 204, since there is nothing left to return. */
    @DeleteMapping("/projects/{projectId}")
    ResponseEntity<Void> delete(@PathVariable UUID projectId, @AuthenticationPrincipal CurrentUser actor) {
        projects.delete(projectId, actor);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/projects/{projectId}/manifest")
    ApiDtos.AxRenewalProjectResponse attachManifest(
            @PathVariable UUID projectId,
            @Valid @RequestBody ApiDtos.AttachManifestRequest request,
            @AuthenticationPrincipal CurrentUser actor) {
        var attached =
                projects.attachManifest(projectId, request.filename(), request.content(), request.stats(), actor);
        return ApiDtos.AxRenewalProjectResponse.of(attached, projects.isMember(projectId, actor));
    }

    /**
     * @param useModel false to run the keyword analysis even where a local
     *     model is configured — the "Disconnect" the workspace offers. A
     *     per-request choice rather than a stored setting, because the
     *     configuration it overrides is one developer's own machine
     *     (ADR-043) and a persisted flag would outlive the reason for it.
     * @param sourcePath one caller's own legacy checkout for this one run,
     *     in place of {@code app.ax-renewal.source-path} — same reasoning as
     *     {@code useModel} above, one step further (ADR-054). Never stored;
     *     {@code AxRenewalService} validates it and reports a bad one as 400.
     */
    @PostMapping("/projects/{projectId}/analysis/run")
    ApiDtos.AxRenewalAnalysisResponse runAnalysis(
            @PathVariable UUID projectId,
            @RequestParam(name = "useModel", defaultValue = "true") boolean useModel,
            @RequestParam(required = false) String sourcePath,
            @AuthenticationPrincipal CurrentUser actor) {
        return ApiDtos.AxRenewalAnalysisResponse.of(projects.runAnalysis(projectId, useModel, sourcePath, actor));
    }

    @PutMapping("/projects/{projectId}/plan/stack-move")
    List<ApiDtos.AxRenewalStackLayerResponse> updateStackMove(
            @PathVariable UUID projectId,
            @Valid @RequestBody ApiDtos.UpdateStackMoveRequest request,
            @AuthenticationPrincipal CurrentUser actor) {
        List<AxRenewalPlanRepository.StackLayerInput> layers = request.layers().stream()
                .map(l -> new AxRenewalPlanRepository.StackLayerInput(l.layerKey(), l.oldTarget(), l.newTarget()))
                .toList();
        return projects.updateStackMove(projectId, layers, request.revision(), actor).stream()
                .map(ApiDtos.AxRenewalStackLayerResponse::of)
                .toList();
    }

    @PutMapping("/projects/{projectId}/plan/architecture")
    ApiDtos.AxRenewalArchitectureResponse updateArchitecture(
            @PathVariable UUID projectId,
            @Valid @RequestBody ApiDtos.UpdateArchitectureRequest request,
            @AuthenticationPrincipal CurrentUser actor) {
        return ApiDtos.AxRenewalArchitectureResponse.of(projects.updateArchitecture(
                projectId, request.summary(), request.diagram(), request.revision(), actor));
    }

    @PutMapping("/projects/{projectId}/plan/pages")
    List<ApiDtos.AxRenewalPageMappingResponse> updatePages(
            @PathVariable UUID projectId,
            @Valid @RequestBody ApiDtos.UpdatePagesRequest request,
            @AuthenticationPrincipal CurrentUser actor) {
        List<AxRenewalPlanRepository.PageMappingInput> mappings = request.pages().stream()
                .map(p -> new AxRenewalPlanRepository.PageMappingInput(p.oldPath(), p.newPath(), p.notes()))
                .toList();
        return projects.updatePages(projectId, mappings, request.revision(), actor).stream()
                .map(ApiDtos.AxRenewalPageMappingResponse::of)
                .toList();
    }

    @PutMapping("/projects/{projectId}/plan/database")
    List<ApiDtos.AxRenewalDbChangeResponse> updateDatabasePlan(
            @PathVariable UUID projectId,
            @Valid @RequestBody ApiDtos.UpdateDatabasePlanRequest request,
            @AuthenticationPrincipal CurrentUser actor) {
        List<AxRenewalPlanRepository.DbChangeInput> changes = request.changes().stream()
                .map(c -> new AxRenewalPlanRepository.DbChangeInput(
                        c.changeType(), c.tableName(), c.detail(), c.rationale()))
                .toList();
        return projects.updateDatabasePlan(projectId, changes, request.revision(), actor).stream()
                .map(ApiDtos.AxRenewalDbChangeResponse::of)
                .toList();
    }

    @PutMapping("/projects/{projectId}/database/connect")
    ApiDtos.AxRenewalDbConnectionResponse connectDatabase(
            @PathVariable UUID projectId,
            @Valid @RequestBody ApiDtos.ConnectDatabaseRequest request,
            @AuthenticationPrincipal CurrentUser actor) {
        return ApiDtos.AxRenewalDbConnectionResponse.of(projects.connectDatabase(
                projectId,
                request.engine(),
                request.host(),
                request.port(),
                request.databaseName(),
                request.schemaName(),
                request.revision(),
                actor));
    }

    @PutMapping("/projects/{projectId}/destination")
    ApiDtos.AxRenewalDestinationResponse setDestination(
            @PathVariable UUID projectId,
            @Valid @RequestBody ApiDtos.SetDestinationRequest request,
            @AuthenticationPrincipal CurrentUser actor) {
        return ApiDtos.AxRenewalDestinationResponse.of(projects.setDestination(
                projectId,
                request.kind(),
                request.target(),
                request.branch(),
                request.notes(),
                request.revision(),
                actor));
    }

    @PostMapping("/projects/{projectId}/plan/{section}/confirm")
    ApiDtos.AxRenewalConfirmationResponse confirm(
            @PathVariable UUID projectId,
            @PathVariable String section,
            @Valid @RequestBody ApiDtos.ConfirmAxRenewalSectionRequest request,
            @AuthenticationPrincipal CurrentUser actor) {
        AxRenewalSection parsed = parseSection(section);
        return ApiDtos.AxRenewalConfirmationResponse.of(
                projects.confirmSection(projectId, parsed, request.revision(), actor));
    }

    @PostMapping("/projects/{projectId}/plan/{section}/reopen")
    ResponseEntity<Void> reopen(
            @PathVariable UUID projectId,
            @PathVariable String section,
            @AuthenticationPrincipal CurrentUser actor) {
        projects.reopenSection(projectId, parseSection(section), actor);
        return ResponseEntity.noContent().build();
    }

    /**
     * @param sourcePath one caller's own legacy checkout to generate from —
     *     only meaningful when this call actually reaches real generation
     *     (a model configured, a filesystem destination, a concrete
     *     confirmed plan); ignored otherwise, same as {@code useModel} above
     *     on Run analysis (ADR-055).
     */
    @PostMapping("/projects/{projectId}/implementation/start")
    ApiDtos.AxRenewalImplementationRunResponse startImplementation(
            @PathVariable UUID projectId,
            @RequestParam(required = false) String sourcePath,
            @AuthenticationPrincipal CurrentUser actor) {
        return ApiDtos.AxRenewalImplementationRunResponse.of(
                projects.startImplementation(projectId, sourcePath, actor));
    }

    /** The files a Generate run actually found different, once it reaches {@code awaiting_review} (ADR-044). */
    @GetMapping("/projects/{projectId}/implementation/{runId}/changes")
    List<ApiDtos.AxRenewalGeneratedChangeResponse> generatedChanges(
            @PathVariable UUID projectId, @PathVariable UUID runId, @AuthenticationPrincipal CurrentUser actor) {
        return projects.findGeneratedChanges(projectId, runId, actor).stream()
                .map(ApiDtos.AxRenewalGeneratedChangeResponse::of)
                .toList();
    }

    /**
     * Applies (Generate → Review) an AI-suggested fix for the project's
     * current Run Locally failure (ADR-062 Phase 2) — the natural next step
     * after {@code run-locally/explain} once a suggestion looks right.
     */
    @PostMapping("/projects/{projectId}/implementation/error-fix/start")
    ApiDtos.AxRenewalImplementationRunResponse startErrorFix(
            @PathVariable UUID projectId, @AuthenticationPrincipal CurrentUser actor) {
        return ApiDtos.AxRenewalImplementationRunResponse.of(projects.startErrorFixImplementation(projectId, actor));
    }

    /** Generates a natural-language UI change against the renewed project's own UI (ADR-062 Phase 2). */
    @PostMapping("/projects/{projectId}/implementation/ui-change/start")
    ApiDtos.AxRenewalImplementationRunResponse startUiChange(
            @PathVariable UUID projectId,
            @Valid @RequestBody ApiDtos.StartUiChangeRequest request,
            @AuthenticationPrincipal CurrentUser actor) {
        return ApiDtos.AxRenewalImplementationRunResponse.of(
                projects.startUiChangeImplementation(projectId, request.description(), actor));
    }

    /** Runs the destination's own tests against a disposable copy of this run's staged changes (ADR-062 Phase 2). */
    @PostMapping("/projects/{projectId}/implementation/{runId}/run-tests")
    ApiDtos.AxRenewalTestRunResponse runTests(
            @PathVariable UUID projectId, @PathVariable UUID runId, @AuthenticationPrincipal CurrentUser actor) {
        return ApiDtos.AxRenewalTestRunResponse.of(projects.runTests(projectId, runId, actor));
    }

    /** Reverts one specific applied run, only while it is still the latest applied state (ADR-062 Phase 2). */
    @PostMapping("/projects/{projectId}/implementation/{runId}/revert")
    ApiDtos.AxRenewalImplementationRunResponse revertImplementation(
            @PathVariable UUID projectId, @PathVariable UUID runId, @AuthenticationPrincipal CurrentUser actor) {
        return ApiDtos.AxRenewalImplementationRunResponse.of(projects.revertImplementation(projectId, runId, actor));
    }

    @PostMapping("/projects/{projectId}/implementation/{runId}/approve")
    ApiDtos.AxRenewalImplementationRunResponse approveImplementation(
            @PathVariable UUID projectId, @PathVariable UUID runId, @AuthenticationPrincipal CurrentUser actor) {
        return ApiDtos.AxRenewalImplementationRunResponse.of(projects.approveImplementation(projectId, runId, actor));
    }

    @PostMapping("/projects/{projectId}/implementation/{runId}/reject")
    ApiDtos.AxRenewalImplementationRunResponse rejectImplementation(
            @PathVariable UUID projectId, @PathVariable UUID runId, @AuthenticationPrincipal CurrentUser actor) {
        return ApiDtos.AxRenewalImplementationRunResponse.of(projects.rejectImplementation(projectId, runId, actor));
    }

    /** Stops a run that is still generating (ADR-049). */
    @PostMapping("/projects/{projectId}/implementation/{runId}/cancel")
    ApiDtos.AxRenewalImplementationRunResponse cancelImplementation(
            @PathVariable UUID projectId, @PathVariable UUID runId, @AuthenticationPrincipal CurrentUser actor) {
        return ApiDtos.AxRenewalImplementationRunResponse.of(projects.cancelImplementation(projectId, runId, actor));
    }

    /** Starts the applied destination as a live local dev server (ADR-048). */
    @PostMapping("/projects/{projectId}/run-locally/start")
    ApiDtos.AxRenewalRunLocallyStatusResponse startRunLocally(
            @PathVariable UUID projectId, @AuthenticationPrincipal CurrentUser actor) {
        return ApiDtos.AxRenewalRunLocallyStatusResponse.of(projects.startRunLocally(projectId, actor));
    }

    @PostMapping("/projects/{projectId}/run-locally/stop")
    ApiDtos.AxRenewalRunLocallyStatusResponse stopRunLocally(
            @PathVariable UUID projectId, @AuthenticationPrincipal CurrentUser actor) {
        return ApiDtos.AxRenewalRunLocallyStatusResponse.of(projects.stopRunLocally(projectId, actor));
    }

    @GetMapping("/projects/{projectId}/run-locally/status")
    ApiDtos.AxRenewalRunLocallyStatusResponse runLocallyStatus(
            @PathVariable UUID projectId, @AuthenticationPrincipal CurrentUser actor) {
        return ApiDtos.AxRenewalRunLocallyStatusResponse.of(projects.runLocallyStatus(projectId, actor));
    }

    /**
     * AI Error Assistant, phase 1 (ADR-062) — read-only, same reasoning as
     * {@code chat} above: nothing is written, and {@code SecurityConfig} sits
     * this at the same read floor as chat, not the write floor Run Locally
     * start/stop and Approve sit at. {@code actor} is taken only so the
     * caller's membership can be checked (ADR-070).
     */
    @PostMapping("/projects/{projectId}/run-locally/explain")
    ApiDtos.AxRenewalChatResponse explainRunLocallyFailure(
            @PathVariable UUID projectId, @AuthenticationPrincipal CurrentUser actor) {
        LocalClaudeChat.Reply reply = projects.explainRunLocallyFailure(projectId, actor);
        return new ApiDtos.AxRenewalChatResponse(reply.text(), reply.readSource());
    }

    @PostMapping("/projects/{projectId}/comments")
    ResponseEntity<ApiDtos.AxRenewalCommentResponse> addComment(
            @PathVariable UUID projectId,
            @Valid @RequestBody ApiDtos.AddAxRenewalCommentRequest request,
            @AuthenticationPrincipal CurrentUser actor) {
        var created = projects.addComment(projectId, request.body(), actor);
        return ResponseEntity.status(201).body(ApiDtos.AxRenewalCommentResponse.of(created));
    }

    /**
     * Hides one comment (ADR-045). 204, because there is nothing left to
     * return and the thread is re-read anyway.
     *
     * <p>Whether the caller is allowed depends on whose comment it is, which
     * is row data — so unlike every other route here, the check lives in the
     * service. {@code SecurityConfig} still holds the floor.
     */
    @DeleteMapping("/projects/{projectId}/comments/{commentId}")
    ResponseEntity<Void> deleteComment(
            @PathVariable UUID projectId,
            @PathVariable UUID commentId,
            @AuthenticationPrincipal CurrentUser actor) {
        projects.deleteComment(projectId, commentId, actor);
        return ResponseEntity.noContent().build();
    }

    /**
     * One chat turn with the local model (ADR-046).
     *
     * <p>Deliberately not a {@code /comments} sibling: a comment is recorded
     * and attributed, this is not recorded at all. Nothing is written by this
     * call, which is why it takes no {@code actor} — who is allowed to reach
     * it is {@code SecurityConfig}'s decision, as everywhere else here, but
     * there is no row to stamp with their name afterwards.
     *
     * <p>Answers 409 rather than 500 when the machine has no Claude Code
     * configured: "this deployment cannot do that" is a state, not a fault.
     */
    @PostMapping("/projects/{projectId}/assistant/chat")
    ApiDtos.AxRenewalChatResponse chat(
            @PathVariable UUID projectId,
            @Valid @RequestBody ApiDtos.AxRenewalChatRequest request,
            @AuthenticationPrincipal CurrentUser actor) {
        List<LocalClaudeChat.Turn> history = request.history() == null
                ? List.of()
                : request.history().stream()
                        .map(turn -> new LocalClaudeChat.Turn(turn.role(), turn.text()))
                        .toList();
        LocalClaudeChat.Reply reply =
                projects.chat(projectId, history, request.message(), request.sourcePath(), actor);
        return new ApiDtos.AxRenewalChatResponse(reply.text(), reply.readSource());
    }

    private static AxRenewalSection parseSection(String value) {
        return AxRenewalSection.parse(value)
                .orElseThrow(() -> ApiException.badRequest("unknown plan section: " + value));
    }
}
