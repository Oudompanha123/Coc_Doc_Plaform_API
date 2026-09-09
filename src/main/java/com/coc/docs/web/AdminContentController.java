package com.coc.docs.web;

import com.coc.docs.auth.CurrentUser;
import com.coc.docs.content.BlockBodyValidator;
import com.coc.docs.content.ValidationResult;
import com.coc.docs.page.PageService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The editorial API.
 *
 * <p>Everything under {@code /api/v1/admin/**} requires the {@code admin} role,
 * enforced in the filter chain rather than here. There is no {@code @PreAuthorize}
 * on these methods on purpose: a second enforcement point is a second thing to
 * keep in agreement, and ADR-011 chose one chokepoint precisely so that "is
 * every route covered" is answerable by reading one file.
 *
 * <p>// req: FR-CONT-003, FR-CONT-005, FR-CONT-012, FR-CONT-013, FR-I18N-003
 */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminContentController {

    private final PageService content;
    private final BlockBodyValidator validator;

    public AdminContentController(PageService content, BlockBodyValidator validator) {
        this.content = content;
        this.validator = validator;
    }

    /**
     * Opens a page for editing, creating a draft from the published version if
     * there is not one already.
     *
     * <p>A POST, not a GET, because it can create a row. A GET that quietly
     * inserted a draft would be a surprise to anything that prefetches links.
     *
     * <p>// req: FR-CONT-003
     */
    @PostMapping("/pages/{pageId}/versions/{locale}/open")
    ApiDtos.VersionResponse open(
            @PathVariable UUID pageId,
            @PathVariable String locale,
            @AuthenticationPrincipal CurrentUser editor) {

        return ApiDtos.VersionResponse.of(
                content.openForEdit(pageId, ContentController.parseLocale(locale), editor));
    }

    /**
     * Saves a draft, refusing a concurrent overwrite with 409.
     *
     * <p>// req: FR-CONT-003, FR-CONT-013
     */
    @PutMapping("/pages/{pageId}/versions/{locale}/draft")
    ApiDtos.VersionResponse saveDraft(
            @PathVariable UUID pageId,
            @PathVariable String locale,
            @Valid @RequestBody ApiDtos.SaveDraftRequest request,
            @AuthenticationPrincipal CurrentUser editor) {

        return ApiDtos.VersionResponse.of(content.saveDraft(
                pageId,
                ContentController.parseLocale(locale),
                request.title(),
                request.description(),
                request.body(),
                request.versionNo(),
                editor));
    }

    /**
     * Publishes the current draft. This is the moment content becomes visible,
     * and the moment no build runs.
     *
     * <p>// req: FR-CONT-005, KPI
     */
    @PostMapping("/pages/{pageId}/versions/{locale}/publish")
    ApiDtos.VersionResponse publish(
            @PathVariable UUID pageId,
            @PathVariable String locale,
            @AuthenticationPrincipal CurrentUser editor) {

        return ApiDtos.VersionResponse.of(
                content.publish(pageId, ContentController.parseLocale(locale), editor));
    }

    /**
     * Version history, newest first, without bodies.
     *
     * <p>// req: FR-CONT-012, FR-AUTH-006
     */
    @GetMapping("/pages/{pageId}/versions/{locale}/history")
    List<ApiDtos.VersionSummaryResponse> history(
            @PathVariable UUID pageId,
            @PathVariable String locale,
            @AuthenticationPrincipal CurrentUser caller) {

        return content.history(pageId, ContentController.parseLocale(locale), caller).stream()
                .map(ApiDtos.VersionSummaryResponse::of)
                .toList();
    }

    /**
     * Translation coverage and staleness.
     *
     * <p>// req: FR-I18N-003, NFR-I18N-01
     */
    @GetMapping("/translation-status")
    List<ApiDtos.TranslationStatusResponse> translationStatus(
            @RequestParam(required = false) String section) {

        return content.translationStatus(section).stream()
                .map(ApiDtos.TranslationStatusResponse::of)
                .toList();
    }

    /**
     * Validates a body without saving it.
     *
     * <p>Exists so the editor can offer a check that is guaranteed to match what
     * a save will do. The TypeScript validator gives instant feedback as someone
     * types, but it is advisory (ADR-012 decision 3) and can drift; this is the
     * authoritative answer, from the same code the write path runs.
     *
     * <p>200 with {@code valid: false} rather than 422 — nothing was refused,
     * the caller asked a question and got an answer.
     *
     * <p>// req: FR-CONT-009, NFR-USE-01
     */
    @PostMapping("/content/validate")
    ApiDtos.ValidateResponse validate(@Valid @RequestBody ApiDtos.ValidateRequest request) {
        ValidationResult result = validator.validate(request.body());
        return new ApiDtos.ValidateResponse(
                result.valid(),
                result.issues().stream()
                        .map(issue -> new ApiDtos.IssueResponse(issue.path(), issue.message()))
                        .toList());
    }

    /**
     * Soft-deletes a page, refusing while other pages link to it.
     *
     * <p>// req: FR-CONT-007
     */
    @org.springframework.web.bind.annotation.DeleteMapping("/pages/{pageId}")
    ResponseEntity<Void> delete(
            @PathVariable UUID pageId, @AuthenticationPrincipal CurrentUser editor) {

        content.delete(pageId, editor);
        return ResponseEntity.noContent().build();
    }
}
