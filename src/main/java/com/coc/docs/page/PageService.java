package com.coc.docs.page;

import com.coc.docs.audit.AuditAction;
import com.coc.docs.audit.AuditRepository;
import com.coc.docs.auth.CurrentUser;
import com.coc.docs.content.BlockBodyValidator;
import com.coc.docs.content.Locale;
import com.coc.docs.content.ValidationResult;
import com.coc.docs.section.Section;
import com.coc.docs.section.SectionRepository;
import com.coc.docs.web.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Content reads and the editorial write path.
 *
 * <p>Everything that constitutes a rule about content lives here rather than in
 * a controller: what a reader may see, when a draft save is refused, what
 * publishing does to the previously published row, and what gets written to the
 * audit trail. A controller that enforced any of this would be a second place
 * to look, and the frontend is not a place any of it can live at all
 * (ADR-012 decision 3).
 *
 * <p>Per-page authorisation lives here too. {@code page.min_role} is a column,
 * and a column is not a control until something compares it — every route that
 * resolves a page runs {@link #requireVisible}, which is the single place that
 * comparison happens.
 *
 * <p>// req: FR-CONT-003, FR-CONT-005, FR-CONT-012, FR-CONT-013, FR-OPS-001,
 * FR-AUTH-006
 */
@Service
public class PageService {

    private final SectionRepository sections;
    private final PageRepository pages;
    private final PageVersionRepository versions;
    private final BlockBodyValidator validator;
    private final AuditRepository audit;
    private final ObjectMapper json;

    public PageService(
            SectionRepository sections,
            PageRepository pages,
            PageVersionRepository versions,
            BlockBodyValidator validator,
            AuditRepository audit,
            ObjectMapper json) {
        this.sections = sections;
        this.pages = pages;
        this.versions = versions;
        this.validator = validator;
        this.audit = audit;
        this.json = json;
    }

    // -----------------------------------------------------------------------
    // Reader path
    // -----------------------------------------------------------------------

    /**
     * What the reader route needs to render one page: the page, its published
     * version in the requested locale, and the section it belongs to.
     */
    public record PublishedPage(Section section, Page page, PageVersion version) {}

    /**
     * Resolved when a slug has been renamed: the caller should redirect.
     *
     * <p>// req: FR-READ-003, CONST-OPS-02
     */
    public record SlugMoved(String sectionSlug, String canonicalSlug) {}

    @Transactional(readOnly = true)
    public List<Section> listSections() {
        return sections.findAll();
    }

    /**
     * Navigation for one section, filtered to what this caller may actually
     * open.
     *
     * <p>The filter is not cosmetic. A nav entry carries the page's slug and its
     * published title, so returning entries the caller cannot read would disclose
     * the two things worth hiding about a restricted page — that it exists and
     * what it is called — while the page body stayed protected. Hiding it in the
     * frontend would disclose them to anyone using curl.
     *
     * <p>// req: FR-AUTH-006, FR-NAV-001, RISK-019
     */
    @Transactional(readOnly = true)
    public List<PageRepository.NavEntry> navigation(
            String sectionSlug, Locale locale, CurrentUser caller) {
        Section section = requireSection(sectionSlug);
        return pages.findNav(section.id(), locale).stream()
                .filter(entry -> caller.isAtLeast(entry.minRole()))
                .toList();
    }

    /**
     * Reads a published page.
     *
     * <p>Three outcomes rather than two, because a renamed slug is neither a hit
     * nor a miss: an inbound link from outside — a bookmark, another document,
     * search — must keep working, so the caller is told where the page went
     * instead of being shown a 404 (CONST-OPS-02).
     *
     * <p>The redirect is guarded as well as the page. A 301 names the canonical
     * slug, so answering one for a page the caller may not read would leak
     * through the redirect exactly what the check on the page itself withholds.
     *
     * <p>// req: FR-READ-001, FR-READ-003, FR-AUTH-006
     */
    @Transactional(readOnly = true)
    public PageLookup readPublished(
            String sectionSlug, String pageSlug, Locale locale, CurrentUser caller) {
        Section section = requireSection(sectionSlug);

        Optional<Page> found = pages.findBySectionAndSlug(section.id(), pageSlug);
        if (found.isEmpty()) {
            Optional<UUID> moved = pages.findByHistoricalSlug(section.id(), pageSlug);
            if (moved.isPresent()) {
                Optional<Page> current = pages.findById(moved.get());
                if (current.isPresent()) {
                    requireVisible(current.get(), caller);
                    return new PageLookup(
                            null, new SlugMoved(section.slug(), current.get().slug()));
                }
            }
            throw ApiException.notFound("page " + sectionSlug + "/" + pageSlug);
        }

        Page page = requireVisible(found.get(), caller);
        PageVersion version = versions
                .findPublished(page.id(), locale)
                .orElseThrow(() -> ApiException.notFound(
                        "page " + sectionSlug + "/" + pageSlug + " in locale " + locale.wire()));

        return new PageLookup(new PublishedPage(section, page, version), null);
    }

    /**
     * The one place {@code page.min_role} is enforced.
     *
     * <p>Every route that resolves a page passes through here, reader and
     * editorial alike, because {@code min_role} governs who may see the content
     * and the editor is a way of seeing it. Without the editorial check an
     * {@code admin} could read a {@code super_admin} page by opening it for
     * editing — the body would arrive in the response either way.
     *
     * <p>403 rather than 404. Every caller here is an authenticated colleague,
     * navigation already omits what they cannot open, and answering 404 would
     * tell someone that a page their colleague is looking at does not exist. The
     * cost is that a caller can confirm a restricted page exists by guessing its
     * slug; that is the deliberate trade, and turning it into a 404 is a
     * one-line change if the threat model later prefers it.
     *
     * <p>// req: FR-AUTH-006, FR-AUTH-005, CONST-TECH-07, TM-003
     */
    private Page requireVisible(Page page, CurrentUser caller) {
        if (!caller.isAtLeast(page.minRole())) {
            throw ApiException.forbidden(
                    "this page requires the " + page.minRole().wire() + " role");
        }
        return page;
    }

    /** Either the page or a redirect, never both. */
    public record PageLookup(PublishedPage found, SlugMoved moved) {

        public boolean isMoved() {
            return moved != null;
        }
    }

    // -----------------------------------------------------------------------
    // Editorial path
    // -----------------------------------------------------------------------

    /**
     * The version an editor should be given to work on.
     *
     * <p>An existing draft if there is one, otherwise a draft branched from the
     * published version, otherwise an empty draft. Branching happens on open, not
     * on first save, so the {@code version_no} the editor is holding is the one
     * the optimistic lock will compare against — deferring it would mean the
     * first save had nothing to check.
     *
     * <p>// req: FR-CONT-003, FR-CONT-013
     */
    @Transactional
    public PageVersion openForEdit(UUID pageId, Locale locale, CurrentUser editor) {
        Page page = requireVisible(
                pages.findById(pageId).orElseThrow(() -> ApiException.notFound("page " + pageId)),
                editor);

        Optional<PageVersion> draft = versions.findDraft(page.id(), locale);
        if (draft.isPresent()) {
            return draft.get();
        }

        Optional<PageVersion> published = versions.findPublished(page.id(), locale);
        if (published.isPresent()) {
            PageVersion source = published.get();
            return versions.insertDraft(
                    page.id(),
                    locale,
                    source.title(),
                    source.description(),
                    source.body(),
                    source.versionNo(),
                    source.sourceVersionId(),
                    editor.id());
        }

        return versions.insertDraft(
                page.id(),
                locale,
                "",
                "",
                json.createArrayNode(),
                1,
                null,
                editor.id());
    }

    /**
     * Saves a draft.
     *
     * <p>Two things have to hold, in this order. The body is validated first, so
     * a malformed body never reaches the database even when the version numbers
     * agree. Then the update is applied with {@code version_no} in the
     * {@code where} clause, so a concurrent save is refused rather than silently
     * overwriting — and the refusal is a 409 the editor sees, not a warning in a
     * log nobody reads.
     *
     * <p>Publishing is untouched by this: the published row is a different row,
     * so readers see nothing until {@link #publish} runs.
     *
     * <p>// req: FR-CONT-003, FR-CONT-013, NFR-SEC-SANITIZE
     */
    @Transactional
    public PageVersion saveDraft(
            UUID pageId,
            Locale locale,
            String title,
            String description,
            JsonNode body,
            int expectedVersionNo,
            CurrentUser editor) {

        Page page = requireVisible(
                pages.findById(pageId).orElseThrow(() -> ApiException.notFound("page " + pageId)),
                editor);

        if (title == null || title.isBlank()) {
            throw ApiException.badRequest("a page version requires a title");
        }

        ValidationResult validation = validator.validate(body);
        if (!validation.valid()) {
            throw ApiException.unprocessable(
                    "the page body does not conform to the block model", validation.issues());
        }

        PageVersion draft = versions
                .findDraft(page.id(), locale)
                .orElseThrow(() -> ApiException.conflict(
                        "there is no draft for this page and locale; open it for editing first"));

        Optional<PageVersion> saved = versions.updateDraft(
                draft.id(), expectedVersionNo, title, description == null ? "" : description, body);

        if (saved.isEmpty()) {
            // The draft exists and the body is valid, so the only remaining
            // explanation is that version_no moved: someone else saved between
            // this editor opening the page and pressing save.
            throw ApiException.conflict(
                    "this page was changed by someone else since you opened it (you have version "
                            + expectedVersionNo + ", the current draft is version "
                            + draft.versionNo() + "). Reload to see their changes before saving.");
        }

        audit.append(
                editor.id(),
                editor.email(),
                AuditAction.PAGE_DRAFT_SAVE,
                AuditAction.TARGET_PAGE,
                page.id(),
                json.createObjectNode()
                        .put("locale", locale.wire())
                        .put("versionNo", saved.get().versionNo())
                        .put("blocks", body.size()));

        return saved.get();
    }

    /**
     * Publishes the current draft.
     *
     * <p>Order matters and is not arbitrary: the published row is archived
     * <em>before</em> the draft is promoted, because the one-published-row index
     * is not deferrable and the reverse order would violate it mid transaction
     * even though the end state is legal.
     *
     * <p>The body is validated again here rather than trusted from the draft
     * save. A draft could have been written before a validator rule was
     * tightened, and publishing is the moment content becomes visible to
     * everyone — the cheaper check belongs at the more consequential boundary.
     *
     * <p>// req: FR-CONT-005, FR-CONT-012, FR-I18N-002, FR-OPS-001
     */
    @Transactional
    public PageVersion publish(UUID pageId, Locale locale, CurrentUser editor) {
        Page page = requireVisible(
                pages.findById(pageId).orElseThrow(() -> ApiException.notFound("page " + pageId)),
                editor);

        PageVersion draft = versions
                .findDraft(page.id(), locale)
                .orElseThrow(() -> ApiException.conflict("there is no draft to publish"));

        ValidationResult validation = validator.validate(draft.body());
        if (!validation.valid()) {
            throw ApiException.unprocessable(
                    "this draft cannot be published: its body does not conform to the block model",
                    validation.issues());
        }
        if (draft.title() == null || draft.title().isBlank()) {
            throw ApiException.unprocessable(
                    "this draft cannot be published without a title", List.of());
        }

        // A translation records which published `en` version it was made from, so
        // staleness stays derivable instead of becoming a flag someone forgets to
        // clear. English is its own reference and records nothing.
        UUID sourceVersionId = locale == Locale.EN
                ? null
                : versions.findPublished(page.id(), Locale.EN).map(PageVersion::id).orElse(null);

        versions.archivePublished(page.id(), locale);

        PageVersion published = versions
                .promoteDraft(draft.id(), sourceVersionId)
                .orElseThrow(() -> ApiException.conflict(
                        "the draft changed while it was being published; try again"));

        audit.append(
                editor.id(),
                editor.email(),
                AuditAction.PAGE_PUBLISH,
                AuditAction.TARGET_PAGE,
                page.id(),
                json.createObjectNode()
                        .put("locale", locale.wire())
                        .put("versionId", published.id().toString())
                        .put("versionNo", published.versionNo())
                        .put("title", published.title()));

        return published;
    }

    /**
     * Version history for one locale.
     *
     * <p>// req: FR-CONT-012, FR-AUTH-006
     */
    @Transactional(readOnly = true)
    public List<PageVersion> history(UUID pageId, Locale locale, CurrentUser caller) {
        requireVisible(
                pages.findById(pageId).orElseThrow(() -> ApiException.notFound("page " + pageId)),
                caller);
        return versions.history(pageId, locale);
    }

    @Transactional(readOnly = true)
    public List<PageVersionRepository.TranslationStatus> translationStatus(String sectionSlug) {
        if (sectionSlug != null) {
            requireSection(sectionSlug);
        }
        return versions.translationStatus(sectionSlug);
    }

    /**
     * Soft-deletes a page, refusing while other pages still link to it.
     *
     * <p>Because links are stored as page references rather than URLs, the
     * inbound set is knowable exactly. Refusing with the list of referring pages
     * is the whole value of that choice — the alternative is a delete that
     * silently breaks links nobody can enumerate.
     *
     * <p>// req: FR-CONT-007
     */
    @Transactional
    public void delete(UUID pageId, CurrentUser editor) {
        Page page = requireVisible(
                pages.findById(pageId).orElseThrow(() -> ApiException.notFound("page " + pageId)),
                editor);

        List<Page> referring = pages.findReferencing(page.id());
        if (!referring.isEmpty()) {
            String slugs = referring.stream().map(Page::slug).sorted().reduce(
                    (a, b) -> a + ", " + b).orElse("");
            throw ApiException.conflict(
                    "this page cannot be deleted while other pages link to it: " + slugs);
        }

        pages.softDelete(page.id());

        audit.append(
                editor.id(),
                editor.email(),
                AuditAction.PAGE_DELETE,
                AuditAction.TARGET_PAGE,
                page.id(),
                json.createObjectNode().put("slug", page.slug()));
    }

    private Section requireSection(String slug) {
        return sections
                .findBySlug(slug)
                .orElseThrow(() -> ApiException.notFound("section " + slug));
    }
}
