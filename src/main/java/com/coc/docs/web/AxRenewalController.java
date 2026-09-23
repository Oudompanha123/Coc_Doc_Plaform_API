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
import org.springframework.web.bind.annotation.GetMapping;
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

    @GetMapping("/projects")
    List<ApiDtos.AxRenewalProjectResponse> list() {
        return projects.list().stream().map(ApiDtos.AxRenewalProjectResponse::of).toList();
    }

    @PostMapping("/projects")
    ResponseEntity<ApiDtos.AxRenewalProjectResponse> create(
            @Valid @RequestBody ApiDtos.CreateAxRenewalProjectRequest request,
            @AuthenticationPrincipal CurrentUser actor) {
        var created = projects.create(request.name(), request.description(), actor);
        return ResponseEntity.created(URI.create("/api/v1/ax-renewal/projects/" + created.id()))
                .body(ApiDtos.AxRenewalProjectResponse.of(created));
    }

    @GetMapping("/projects/{projectId}")
    ApiDtos.AxRenewalProjectDetailResponse detail(@PathVariable UUID projectId) {
        return ApiDtos.AxRenewalProjectDetailResponse.of(projects.detail(projectId));
    }

    @PostMapping("/projects/{projectId}/manifest")
    ApiDtos.AxRenewalProjectResponse attachManifest(
            @PathVariable UUID projectId,
            @Valid @RequestBody ApiDtos.AttachManifestRequest request,
            @AuthenticationPrincipal CurrentUser actor) {
        return ApiDtos.AxRenewalProjectResponse.of(
                projects.attachManifest(projectId, request.filename(), request.content(), request.stats(), actor));
    }

    /**
     * @param useModel false to run the keyword analysis even where a local
     *     model is configured — the "Disconnect" the workspace offers. A
     *     per-request choice rather than a stored setting, because the
     *     configuration it overrides is one developer's own machine
     *     (ADR-043) and a persisted flag would outlive the reason for it.
     */
    @PostMapping("/projects/{projectId}/analysis/run")
    ApiDtos.AxRenewalAnalysisResponse runAnalysis(
            @PathVariable UUID projectId,
            @RequestParam(name = "useModel", defaultValue = "true") boolean useModel,
            @AuthenticationPrincipal CurrentUser actor) {
        return ApiDtos.AxRenewalAnalysisResponse.of(projects.runAnalysis(projectId, useModel, actor));
    }

    @PutMapping("/projects/{projectId}/plan/stack-move")
    List<ApiDtos.AxRenewalStackLayerResponse> updateStackMove(
            @PathVariable UUID projectId,
            @Valid @RequestBody ApiDtos.UpdateStackMoveRequest request,
            @AuthenticationPrincipal CurrentUser actor) {
        List<AxRenewalPlanRepository.StackLayerInput> layers = request.layers().stream()
                .map(l -> new AxRenewalPlanRepository.StackLayerInput(l.layerKey(), l.oldTarget(), l.newTarget()))
                .toList();
        return projects.updateStackMove(projectId, layers, actor).stream()
                .map(ApiDtos.AxRenewalStackLayerResponse::of)
                .toList();
    }

    @PutMapping("/projects/{projectId}/plan/architecture")
    ApiDtos.AxRenewalArchitectureResponse updateArchitecture(
            @PathVariable UUID projectId,
            @RequestBody ApiDtos.UpdateArchitectureRequest request,
            @AuthenticationPrincipal CurrentUser actor) {
        return ApiDtos.AxRenewalArchitectureResponse.of(
                projects.updateArchitecture(projectId, request.summary(), request.diagram(), actor));
    }

    @PutMapping("/projects/{projectId}/plan/pages")
    List<ApiDtos.AxRenewalPageMappingResponse> updatePages(
            @PathVariable UUID projectId,
            @Valid @RequestBody ApiDtos.UpdatePagesRequest request,
            @AuthenticationPrincipal CurrentUser actor) {
        List<AxRenewalPlanRepository.PageMappingInput> mappings = request.pages().stream()
                .map(p -> new AxRenewalPlanRepository.PageMappingInput(p.oldPath(), p.newPath(), p.notes()))
                .toList();
        return projects.updatePages(projectId, mappings, actor).stream()
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
        return projects.updateDatabasePlan(projectId, changes, actor).stream()
                .map(ApiDtos.AxRenewalDbChangeResponse::of)
                .toList();
    }

    @PutMapping("/projects/{projectId}/database/connect")
    ApiDtos.AxRenewalDbConnectionResponse connectDatabase(
            @PathVariable UUID projectId,
            @RequestBody ApiDtos.ConnectDatabaseRequest request,
            @AuthenticationPrincipal CurrentUser actor) {
        return ApiDtos.AxRenewalDbConnectionResponse.of(projects.connectDatabase(
                projectId,
                request.engine(),
                request.host(),
                request.port(),
                request.databaseName(),
                request.schemaName(),
                actor));
    }

    @PutMapping("/projects/{projectId}/destination")
    ApiDtos.AxRenewalDestinationResponse setDestination(
            @PathVariable UUID projectId,
            @Valid @RequestBody ApiDtos.SetDestinationRequest request,
            @AuthenticationPrincipal CurrentUser actor) {
        return ApiDtos.AxRenewalDestinationResponse.of(projects.setDestination(
                projectId, request.kind(), request.target(), request.branch(), request.notes(), actor));
    }

    @PostMapping("/projects/{projectId}/plan/{section}/confirm")
    ApiDtos.AxRenewalConfirmationResponse confirm(
            @PathVariable UUID projectId,
            @PathVariable String section,
            @AuthenticationPrincipal CurrentUser actor) {
        AxRenewalSection parsed = parseSection(section);
        return ApiDtos.AxRenewalConfirmationResponse.of(projects.confirmSection(projectId, parsed, actor));
    }

    @PostMapping("/projects/{projectId}/plan/{section}/reopen")
    ResponseEntity<Void> reopen(
            @PathVariable UUID projectId,
            @PathVariable String section,
            @AuthenticationPrincipal CurrentUser actor) {
        projects.reopenSection(projectId, parseSection(section), actor);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/projects/{projectId}/implementation/start")
    ApiDtos.AxRenewalImplementationRunResponse startImplementation(
            @PathVariable UUID projectId, @AuthenticationPrincipal CurrentUser actor) {
        return ApiDtos.AxRenewalImplementationRunResponse.of(projects.startImplementation(projectId, actor));
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
     * One chat turn with the local model (ADR-044).
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
            @PathVariable UUID projectId, @Valid @RequestBody ApiDtos.AxRenewalChatRequest request) {
        List<LocalClaudeChat.Turn> history = request.history() == null
                ? List.of()
                : request.history().stream()
                        .map(turn -> new LocalClaudeChat.Turn(turn.role(), turn.text()))
                        .toList();
        LocalClaudeChat.Reply reply = projects.chat(projectId, history, request.message());
        return new ApiDtos.AxRenewalChatResponse(reply.text(), reply.readSource());
    }

    private static AxRenewalSection parseSection(String value) {
        return AxRenewalSection.parse(value)
                .orElseThrow(() -> ApiException.badRequest("unknown plan section: " + value));
    }
}
