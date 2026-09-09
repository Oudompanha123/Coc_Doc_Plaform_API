package com.coc.docs.web;

import com.coc.docs.account.Account;
import com.coc.docs.auth.CurrentUser;
import com.coc.docs.page.Page;
import com.coc.docs.page.PageRepository;
import com.coc.docs.page.PageService;
import com.coc.docs.page.PageVersion;
import com.coc.docs.page.PageVersionRepository;
import com.coc.docs.section.Section;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The wire contract, in one file.
 *
 * <p>Domain records are not serialised directly. That is not ceremony: the
 * frontend consumes this API and nothing else does (ADR-012 decision 1), so the
 * shape of these types <em>is</em> the contract, and a field added to a domain
 * record should not silently appear in it. It also keeps things that must never
 * be sent — a session token hash, an internal id an editor has no use for —
 * absent by construction rather than by an annotation someone has to remember.
 *
 * <p>// req: CONST-TECH-05, ADR-012
 */
public final class ApiDtos {

    private ApiDtos() {}

    // -----------------------------------------------------------------------
    // Auth
    // -----------------------------------------------------------------------

    /** The sign-in request: a Google ID token and nothing else. */
    public record GoogleSignInRequest(@NotBlank String idToken) {}

    /**
     * Who the caller is.
     *
     * <p>The role is included so the frontend can hide what a caller cannot use.
     * That is a courtesy, not a control — the same role is checked server-side on
     * every request, and a client that lies to itself about this gains nothing
     * (ADR-011, alternative B3).
     */
    public record MeResponse(
            UUID id, String email, String displayName, String role, boolean authenticated) {

        public static MeResponse of(CurrentUser user) {
            return new MeResponse(
                    user.id(), user.email(), user.account().displayName(), user.role().wire(), true);
        }

        /** For a caller with no usable session. Not an error: the reader UI asks first. */
        public static MeResponse anonymous() {
            return new MeResponse(null, null, null, null, false);
        }
    }

    // -----------------------------------------------------------------------
    // Reader path
    // -----------------------------------------------------------------------

    public record SectionResponse(UUID id, String slug, int order, boolean dbBacked) {

        public static SectionResponse of(Section section) {
            return new SectionResponse(
                    section.id(), section.slug(), section.order(), section.dbBacked());
        }
    }

    /** One navigation entry. Carries the published title, so a nav render is one call. */
    public record NavEntryResponse(UUID id, String slug, int order, String badge, String title) {

        public static NavEntryResponse of(PageRepository.NavEntry entry) {
            return new NavEntryResponse(
                    entry.id(), entry.slug(), entry.order(), entry.badge(), entry.title());
        }
    }

    /**
     * A published page, ready to render.
     *
     * <p>{@code body} passes through as raw {@link JsonNode}. The backend
     * validates it and stores it; only the frontend turns blocks into elements
     * (ADR-012), so re-modelling it here would be a translation with no reader.
     */
    public record PageResponse(
            UUID id,
            String sectionSlug,
            String slug,
            String locale,
            String title,
            String description,
            String badge,
            JsonNode body,
            int versionNo,
            Instant updatedAt) {

        public static PageResponse of(PageService.PublishedPage published) {
            Page page = published.page();
            PageVersion version = published.version();
            return new PageResponse(
                    page.id(),
                    published.section().slug(),
                    page.slug(),
                    version.locale().wire(),
                    version.title(),
                    version.description(),
                    page.badge(),
                    version.body(),
                    version.versionNo(),
                    version.updatedAt());
        }
    }

    /**
     * Where a renamed page went.
     *
     * <p>Returned with 301 and a {@code Location} header. The body repeats it so
     * the frontend can build its own redirect without parsing an API URL back
     * into route segments.
     *
     * <p>// req: FR-READ-003
     */
    public record MovedResponse(String sectionSlug, String canonicalSlug) {}

    // -----------------------------------------------------------------------
    // Editorial path
    // -----------------------------------------------------------------------

    /**
     * A draft save.
     *
     * <p>{@code versionNo} is required and has no default. An editor client that
     * omitted it would be asking to overwrite whatever is there, which is the
     * exact behaviour FR-CONT-013 exists to prevent — so a missing value is a
     * 400, not an implied "latest".
     */
    public record SaveDraftRequest(
            @NotBlank String title,
            String description,
            @NotNull JsonNode body,
            @NotNull Integer versionNo) {}

    /** A version, as an editor sees it. */
    public record VersionResponse(
            UUID id,
            UUID pageId,
            String locale,
            String status,
            String title,
            String description,
            JsonNode body,
            int versionNo,
            UUID sourceVersionId,
            Instant createdAt,
            Instant updatedAt) {

        public static VersionResponse of(PageVersion version) {
            return new VersionResponse(
                    version.id(),
                    version.pageId(),
                    version.locale().wire(),
                    version.status().wire(),
                    version.title(),
                    version.description(),
                    version.body(),
                    version.versionNo(),
                    version.sourceVersionId(),
                    version.createdAt(),
                    version.updatedAt());
        }
    }

    /**
     * One history entry, without the body.
     *
     * <p>A history list of a hundred versions would otherwise carry a hundred
     * full page bodies to render a list of dates. The body is fetched per version
     * when someone actually looks at one.
     *
     * <p>// req: FR-CONT-012
     */
    public record VersionSummaryResponse(
            UUID id, String locale, String status, String title, int versionNo, Instant updatedAt) {

        public static VersionSummaryResponse of(PageVersion version) {
            return new VersionSummaryResponse(
                    version.id(),
                    version.locale().wire(),
                    version.status().wire(),
                    version.title(),
                    version.versionNo(),
                    version.updatedAt());
        }
    }

    /**
     * Translation coverage and staleness, reported as two separate fields.
     *
     * <p>They are different measures and conflating them was AMB-002: a missing
     * translation is not a stale one, and an editor needs to act differently on
     * each.
     *
     * <p>// req: FR-I18N-003, NFR-I18N-01
     */
    public record TranslationStatusResponse(
            UUID pageId,
            String sectionSlug,
            String pageSlug,
            String locale,
            boolean published,
            boolean stale) {

        public static TranslationStatusResponse of(PageVersionRepository.TranslationStatus row) {
            return new TranslationStatusResponse(
                    row.pageId(),
                    row.sectionSlug(),
                    row.pageSlug(),
                    row.locale(),
                    row.existsPublished(),
                    row.stale());
        }
    }

    /** The advisory validate endpoint, so the editor can check without saving. */
    public record ValidateRequest(@NotNull JsonNode body) {}

    public record ValidateResponse(boolean valid, List<IssueResponse> issues) {}

    public record IssueResponse(String path, String message) {}

    // -----------------------------------------------------------------------
    // Accounts
    // -----------------------------------------------------------------------

    public record AccountResponse(
            UUID id,
            String email,
            String displayName,
            String role,
            boolean active,
            Instant createdAt) {

        public static AccountResponse of(Account account) {
            return new AccountResponse(
                    account.id(),
                    account.email(),
                    account.displayName(),
                    account.role().wire(),
                    account.isActive(),
                    account.createdAt());
        }
    }

    /**
     * A role change or an activation change, or both.
     *
     * <p>Both fields are nullable and an absent field means "leave alone", so
     * that changing a role does not require restating whether the account is
     * active and risk toggling it by omission.
     */
    public record UpdateAccountRequest(String role, Boolean active) {}
}
