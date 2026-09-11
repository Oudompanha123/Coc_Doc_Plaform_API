package com.coc.docs.web;

import com.coc.docs.account.Account;
import com.coc.docs.account.AppRole;
import com.coc.docs.account.Permission;
import com.coc.docs.account.RoleRepository;
import com.coc.docs.auth.CurrentUser;
import com.coc.docs.page.Page;
import com.coc.docs.page.PageRepository;
import com.coc.docs.page.PageService;
import com.coc.docs.page.PageVersion;
import com.coc.docs.page.PageVersionRepository;
import com.coc.docs.section.Section;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.Email;
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

    /**
     * The sign-in request: an ID token and nothing else.
     *
     * <p>No nonce field, deliberately. The expected nonce is read from the
     * {@code coc_oidc_nonce} cookie, because a value the caller supplies
     * cannot be evidence about the caller — a replayer would send the captured
     * token together with the nonce baked into it and both would agree.
     */
    public record GoogleSignInRequest(@NotBlank String idToken) {}

    /**
     * The nonce for one sign-in attempt.
     *
     * <p>Returned in the body so the caller can pass it to Keycloak, and set
     * as an {@code httpOnly} cookie so the backend keeps a copy the caller
     * cannot choose. Both, deliberately: the body copy is an instruction, the
     * cookie copy is the evidence.
     */
    public record NonceResponse(String nonce) {}

    /**
     * Who the caller is.
     *
     * <p>The role is included so the frontend can hide what a caller cannot use.
     * That is a courtesy, not a control — the same role is checked server-side on
     * every request, and a client that lies to itself about this gains nothing
     * (ADR-011, alternative B3).
     *
     * <p>This carries personal data (email, display name) from the one route the
     * filter chain leaves open, so it is where NFR-SEC-PII is most easily broken.
     * It holds because the payload is built from the caller's own resolved
     * session and never from a lookup the caller names: there is no parameter
     * here to point at somebody else. An unauthenticated caller gets
     * {@link #anonymous()}, which carries nothing.
     *
     * <p>// req: NFR-SEC-PII, FR-AUTH-005
     */
    public record MeResponse(
            UUID id,
            String email,
            String displayName,
            String role,
            String avatarUrl,
            boolean authenticated) {

        public static MeResponse of(CurrentUser user) {
            return new MeResponse(
                    user.id(),
                    user.email(),
                    user.account().displayName(),
                    user.role().wire(),
                    user.account().avatarUrl(),
                    true);
        }

        /**
         * For a caller with no usable session. Not an error: the reader UI asks first.
         *
         * <p>Every field is null rather than absent or defaulted. This is the
         * response an unauthenticated request to {@code /auth/me} receives, and
         * NFR-SEC-PII's "never exposed to guest or unauthenticated requests" is
         * satisfied here or not at all. // req: NFR-SEC-PII
         */
        public static MeResponse anonymous() {
            return new MeResponse(null, null, null, null, null, false);
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

    /**
     * One account, for the administration screen.
     *
     * <p>The densest personal data in the API: every account's email and display
     * name in one list. It is reachable only through
     * {@code /api/v1/admin/accounts/**}, which the filter chain floors at
     * {@code super_admin} — above the {@code admin} floor the rest of
     * {@code /admin/**} carries (FR-AUTH-008). Nothing else in the API returns
     * another person's email, which is what keeps NFR-SEC-PII to one guarded
     * route rather than a property to re-check on every response.
     *
     * <p>// req: NFR-SEC-PII, FR-AUTH-008
     */
    public record AccountResponse(
            UUID id,
            String email,
            String displayName,
            String role,
            boolean active,
            Instant createdAt,
            Instant lastSignInAt,
            /**
             * Every role held, rank order. {@code role} above is the highest
             * of these and is what authorisation compares against; this is
             * here so an administrator can see the whole grant rather than
             * its effect (ADR-016).
             */
            List<String> roles) {

        public static AccountResponse of(Account account) {
            return of(account, null);
        }

        public static AccountResponse of(Account account, Instant lastSignInAt) {
            return new AccountResponse(
                    account.id(),
                    account.email(),
                    account.displayName(),
                    account.role().wire(),
                    account.isActive(),
                    account.createdAt(),
                    lastSignInAt,
                    account.roles().stream()
                            .sorted(java.util.Comparator.comparingInt(Enum::ordinal))
                            .map(AppRole::wire)
                            .toList());
        }
    }

    /**
     * A role change or an activation change, or both.
     *
     * <p>Both fields are nullable and an absent field means "leave alone", so
     * that changing a role does not require restating whether the account is
     * active and risk toggling it by omission.
     */
    /**
     * A change to a role, an activation state, a display name, or any
     * combination. An absent field means "leave alone".
     */
    public record UpdateAccountRequest(String role, Boolean active, String displayName) {}

    /**
     * One role and what it grants.
     *
     * <p>Sent so the administration screen can show what a role means rather
     * than leaving someone to infer it from the name. Read-only: roles are
     * seeded by migration, not created here (ADR-016).
     */
    public record RoleResponse(
            String key, int rank, String description, List<String> permissions, int accountCount) {

        public static RoleResponse of(RoleRepository.RoleDetail detail) {
            return new RoleResponse(
                    detail.role().wire(),
                    detail.rank(),
                    detail.description(),
                    detail.permissions().stream().map(Permission::key).toList(),
                    detail.accountCount());
        }
    }

    /**
     * An account a {@code super_admin} creates before its owner first signs in.
     *
     * <p>Email and role only. No password — the person authenticates with
     * Google — and no status, because a {@code super_admin} creating the
     * account is itself the approval.
     */
    public record CreateAccountRequest(
            @NotBlank @Email String email, @NotBlank String role) {}
}
