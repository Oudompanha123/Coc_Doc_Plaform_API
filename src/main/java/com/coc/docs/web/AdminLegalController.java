package com.coc.docs.web;

import com.coc.docs.account.Account;
import com.coc.docs.account.AccountRepository;
import com.coc.docs.auth.CurrentUser;
import com.coc.docs.legal.LegalPageService;
import com.coc.docs.legal.LegalPageVersion;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The editorial API for Legal pages (Terms of Service / Privacy Policy).
 *
 * <p>Every route here sits under {@code /api/v1/admin/**}, floored at {@code
 * admin} in {@code SecurityConfig} — but the write routes ({@code PUT} draft,
 * {@code POST} publish, {@code POST} restore) carry their own, narrower
 * {@code super_admin}-only matchers there too, so an {@code admin} can open
 * this page and read it but any write attempt is refused at the filter chain
 * before it reaches this controller — no {@code @PreAuthorize} here, same
 * one-chokepoint doctrine {@link AdminContentController} documents.
 */
@RestController
@RequestMapping("/api/v1/admin/legal")
public class AdminLegalController {

    private final LegalPageService legal;
    private final AccountRepository accounts;

    public AdminLegalController(LegalPageService legal, AccountRepository accounts) {
        this.legal = legal;
        this.accounts = accounts;
    }

    /**
     * Opens a page for editing, creating a draft from the published version
     * if there is not one already. A GET, unlike {@code PageService}'s own
     * {@code /open} (a POST there) — Legal's draft always exists after the
     * seed migration, so this almost never creates a row, and treating the
     * ordinary case as a read keeps it cacheable/prefetch-safe.
     */
    @GetMapping("/{slug}/draft")
    ApiDtos.LegalPageVersionResponse openDraft(@PathVariable String slug) {
        return respond(legal.openForEdit(LegalController.parseSlug(slug)));
    }

    /** Saves a draft, refusing a concurrent overwrite with 409. */
    @PutMapping("/{slug}/draft")
    ApiDtos.LegalPageVersionResponse saveDraft(
            @PathVariable String slug,
            @Valid @RequestBody ApiDtos.SaveLegalDraftRequest request,
            @AuthenticationPrincipal CurrentUser editor) {

        return respond(legal.saveDraft(
                LegalController.parseSlug(slug), request.content(), request.versionNo(), editor));
    }

    /** Publishes the current draft, with an optional note (task spec §G) —
     *  a client posting no body at all (every call site before that field
     *  existed) publishes with none. */
    @PostMapping("/{slug}/publish")
    ApiDtos.LegalPageVersionResponse publish(
            @PathVariable String slug,
            @RequestBody(required = false) ApiDtos.PublishLegalPageRequest request,
            @AuthenticationPrincipal CurrentUser editor) {

        String note = request == null ? null : request.note();
        return respond(legal.publish(LegalController.parseSlug(slug), note, editor));
    }

    /**
     * Published/archived versions, newest first — no content, so the list
     * stays light. Each row carries its publisher's name and photo.
     *
     * <p>A row with no publisher is either the version V68 seeded with the
     * table (no account existed yet) or one whose publisher's account was
     * later deleted ({@code on delete set null}) — publishing from the editor
     * always records the person. The seeded row is always a page's earliest
     * version, so only that one is flagged {@code initialVersion}; any other
     * row without a publisher is not passed off as seeded.
     */
    @GetMapping("/{slug}/versions")
    List<ApiDtos.LegalVersionSummaryResponse> versions(@PathVariable String slug) {
        List<LegalPageVersion> history = legal.history(LegalController.parseSlug(slug));
        UUID earliest = history.isEmpty() ? null : history.get(history.size() - 1).id();
        return history.stream()
                .map(v -> ApiDtos.LegalVersionSummaryResponse.of(v, publisher(v), v.id().equals(earliest)))
                .toList();
    }

    /** One specific version's full content — the History panel's "Preview", which (unlike restore) never touches the draft. */
    @GetMapping("/{slug}/versions/{versionId}")
    ApiDtos.LegalPageVersionResponse version(@PathVariable String slug, @PathVariable UUID versionId) {
        return respond(legal.getHistoryVersion(LegalController.parseSlug(slug), versionId));
    }

    /** Copies an old version's content back into the current draft. Never publishes by itself. */
    @PostMapping("/{slug}/versions/{versionId}/restore")
    ApiDtos.LegalPageVersionResponse restore(
            @PathVariable String slug,
            @PathVariable UUID versionId,
            @AuthenticationPrincipal CurrentUser editor) {

        return respond(legal.restore(LegalController.parseSlug(slug), versionId, editor));
    }

    private ApiDtos.LegalPageVersionResponse respond(LegalPageVersion version) {
        return ApiDtos.LegalPageVersionResponse.of(version, publisherName(version));
    }

    private String publisherName(LegalPageVersion version) {
        Account publisher = publisher(version);
        return publisher == null ? null : publisher.displayName();
    }

    /** Whoever published this version, or null — none recorded, or their account is gone. */
    private Account publisher(LegalPageVersion version) {
        if (version.publishedBy() == null) {
            return null;
        }
        return accounts.findById(version.publishedBy()).orElse(null);
    }
}
