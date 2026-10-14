package com.coc.docs.legal;

import com.coc.docs.audit.AuditAction;
import com.coc.docs.audit.AuditRepository;
import com.coc.docs.auth.CurrentUser;
import com.coc.docs.content.ValidationResult;
import com.coc.docs.page.VersionStatus;
import com.coc.docs.web.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Legal page (Terms of Service / Privacy Policy) reads and the editorial
 * write path — the {@code legal_page_version} analogue of {@link
 * com.coc.docs.page.PageService}.
 *
 * <p>Role enforcement for the write methods here lives in {@code
 * SecurityConfig} (super_admin only), not in this class — ADR-011's one
 * authorization chokepoint doctrine, same as {@code PageService}: a second
 * enforcement point here would be a second place that has to agree with the
 * route table.
 */
@Service
public class LegalPageService {

    private final LegalPageVersionRepository versions;
    private final LegalDocumentValidator documentValidator;
    private final AuditRepository audit;
    private final ObjectMapper json;

    public LegalPageService(
            LegalPageVersionRepository versions,
            LegalDocumentValidator documentValidator,
            AuditRepository audit,
            ObjectMapper json) {
        this.versions = versions;
        this.documentValidator = documentValidator;
        this.audit = audit;
        this.json = json;
    }

    /**
     * Both slugs share one shape since V81 — title, byline and one Markdown
     * body — so one validator. {@code slug} stays in the signature because a
     * per-slug rule is the likeliest next change.
     */
    @SuppressWarnings("unused")
    private ValidationResult validate(LegalSlug slug, JsonNode content) {
        return documentValidator.validate(content);
    }

    // -----------------------------------------------------------------------
    // Reader path
    // -----------------------------------------------------------------------

    /** The latest published content for a slug — what {@code /terms}/{@code /privacy} render. */
    @Transactional(readOnly = true)
    public LegalPageVersion readPublished(LegalSlug slug) {
        return versions
                .findPublished(slug)
                .orElseThrow(() -> ApiException.notFound("published " + slug.wire() + " page"));
    }

    // -----------------------------------------------------------------------
    // Editorial path
    // -----------------------------------------------------------------------

    /**
     * The draft an admin editor should see: the existing one if there is
     * one, otherwise a fresh draft branched from the published version,
     * otherwise an empty one. Branching on open (not first save) is what
     * lets the optimistic lock compare against a number the editor actually
     * saw — same reasoning as {@code PageService.openForEdit}.
     */
    @Transactional
    public LegalPageVersion openForEdit(LegalSlug slug) {
        Optional<LegalPageVersion> draft = versions.findDraft(slug);
        if (draft.isPresent()) {
            return draft.get();
        }

        Optional<LegalPageVersion> published = versions.findPublished(slug);
        if (published.isPresent()) {
            LegalPageVersion source = published.get();
            return versions.insertDraft(slug, source.content(), source.versionNo());
        }

        return versions.insertDraft(slug, json.createObjectNode(), 1);
    }

    /**
     * Saves a draft. The content is validated first, so a malformed body
     * never reaches the database even when the version numbers agree; the
     * update then applies {@code version_no} in its {@code where} clause, so
     * a concurrent save is refused with 409 rather than silently overwritten
     * (FR-CONT-013's pattern).
     */
    @Transactional
    public LegalPageVersion saveDraft(
            LegalSlug slug, JsonNode content, int expectedVersionNo, CurrentUser editor) {

        ValidationResult validation = validate(slug, content);
        if (!validation.valid()) {
            throw ApiException.unprocessable(
                    "this content does not pass validation", validation.issues());
        }

        LegalPageVersion draft = versions
                .findDraft(slug)
                .orElseThrow(() -> ApiException.conflict(
                        "there is no draft for " + slug.wire() + "; reload the page"));

        LegalPageVersion saved = versions
                .updateDraft(draft.id(), expectedVersionNo, content)
                .orElseThrow(() -> ApiException.conflict(
                        "this page was changed by someone else since you opened it (you have version "
                                + expectedVersionNo + ", the current draft is version " + draft.versionNo()
                                + "). Reload to see their changes before saving."));

        audit.append(
                editor.id(),
                editor.email(),
                AuditAction.LEGAL_DRAFT_SAVE,
                AuditAction.TARGET_LEGAL_PAGE_VERSION,
                saved.id(),
                json.createObjectNode().put("slug", slug.wire()).put("versionNo", saved.versionNo()));

        return saved;
    }

    /**
     * Publishes the current draft. The content is re-validated here rather
     * than trusted from the last save — a draft could predate a tightened
     * validation rule, and publishing is the moment it becomes visible to
     * everyone. Archiving the old published row happens before promoting the
     * draft: the one-published-row index is not deferrable, so the reverse
     * order would violate it mid transaction even though the end state is
     * legal.
     */
    /** Publishes with no note — every existing call site before the task
     *  spec's "Publish note" field existed. */
    @Transactional
    public LegalPageVersion publish(LegalSlug slug, CurrentUser editor) {
        return publish(slug, null, editor);
    }

    @Transactional
    public LegalPageVersion publish(LegalSlug slug, String note, CurrentUser editor) {
        LegalPageVersion draft = versions
                .findDraft(slug)
                .orElseThrow(() -> ApiException.conflict("there is no draft to publish"));

        ValidationResult validation = validate(slug, draft.content());
        if (!validation.valid()) {
            throw ApiException.unprocessable(
                    "this draft cannot be published: it does not pass validation", validation.issues());
        }

        versions.archivePublished(slug);

        LegalPageVersion published = versions
                .promoteDraft(draft.id(), editor.id(), note)
                .orElseThrow(() -> ApiException.conflict(
                        "the draft changed while it was being published; try again"));

        audit.append(
                editor.id(),
                editor.email(),
                AuditAction.LEGAL_PUBLISH,
                AuditAction.TARGET_LEGAL_PAGE_VERSION,
                published.id(),
                json.createObjectNode().put("slug", slug.wire()).put("versionNo", published.versionNo()));

        return published;
    }

    /** Published/archived versions for one slug, newest first — the History panel's list. */
    @Transactional(readOnly = true)
    public List<LegalPageVersion> history(LegalSlug slug) {
        return versions.history(slug);
    }

    /** One specific published/archived version, content included — the
     *  History panel's "Preview" action, which (unlike Restore) never
     *  touches the draft. */
    @Transactional(readOnly = true)
    public LegalPageVersion getHistoryVersion(LegalSlug slug, java.util.UUID versionId) {
        return findHistoryVersion(slug, versionId);
    }

    private LegalPageVersion findHistoryVersion(LegalSlug slug, java.util.UUID versionId) {
        return versions
                .findById(versionId)
                .filter(v -> v.slug() == slug)
                .filter(v -> v.status() == VersionStatus.PUBLISHED || v.status() == VersionStatus.ARCHIVED)
                .orElseThrow(() -> ApiException.notFound("version " + versionId + " for " + slug.wire()));
    }

    /**
     * Copies an old version's content back into the current draft. Never
     * publishes by itself — the admin still has to review and press
     * Publish, same as any other draft edit.
     */
    @Transactional
    public LegalPageVersion restore(LegalSlug slug, java.util.UUID versionId, CurrentUser editor) {
        LegalPageVersion target = findHistoryVersion(slug, versionId);

        LegalPageVersion draft = versions
                .findDraft(slug)
                .orElseThrow(() -> ApiException.conflict(
                        "there is no draft for " + slug.wire() + "; reload the page"));

        LegalPageVersion restored = versions
                .restoreContent(draft.id(), target.content())
                .orElseThrow(() -> ApiException.conflict(
                        "this page was changed by someone else; reload and try again"));

        audit.append(
                editor.id(),
                editor.email(),
                AuditAction.LEGAL_RESTORE,
                AuditAction.TARGET_LEGAL_PAGE_VERSION,
                restored.id(),
                json.createObjectNode()
                        .put("slug", slug.wire())
                        .put("restoredFromVersionId", target.id().toString()));

        return restored;
    }
}
