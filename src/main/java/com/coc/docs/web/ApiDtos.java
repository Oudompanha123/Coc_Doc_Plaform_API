package com.coc.docs.web;

import com.coc.docs.account.Account;
import com.coc.docs.account.AppRole;
import com.coc.docs.account.Permission;
import com.coc.docs.account.RoleRepository;
import com.coc.docs.auth.CurrentUser;
import com.coc.docs.axrenewal.AxRenewalAnalysis;
import com.coc.docs.axrenewal.AxRenewalArchitecturePlan;
import com.coc.docs.axrenewal.AxRenewalComment;
import com.coc.docs.axrenewal.AxRenewalConfirmation;
import com.coc.docs.axrenewal.AxRenewalDbChange;
import com.coc.docs.axrenewal.AxRenewalDbConnection;
import com.coc.docs.axrenewal.AxRenewalDestination;
import com.coc.docs.axrenewal.AxRenewalGeneratedChange;
import com.coc.docs.axrenewal.AxRenewalImplementationRun;
import com.coc.docs.axrenewal.AxRenewalLocalRunner;
import com.coc.docs.axrenewal.AxRenewalPageMapping;
import com.coc.docs.axrenewal.AxRenewalProject;
import com.coc.docs.axrenewal.AxRenewalProjectDetail;
import com.coc.docs.axrenewal.AxRenewalStackLayer;
import com.coc.docs.page.Page;
import com.coc.docs.page.PageRepository;
import com.coc.docs.page.PageService;
import com.coc.docs.page.PageVersion;
import com.coc.docs.notify.ActivityDigestService;
import com.coc.docs.notify.HrmsMailContent;
import com.coc.docs.notify.HrmsNotificationService;
import com.coc.docs.notify.NotificationFeedService;
import com.coc.docs.page.PageVersionRepository;
import com.coc.docs.section.Section;
import com.coc.docs.claudeusage.ClaudeUsageDailyPoint;
import com.coc.docs.claudeusage.ClaudeUsageDashboard;
import com.coc.docs.claudeusage.ClaudeUsageMemberUsage;
import com.coc.docs.claudeusage.ClaudeUsageMyDashboard;
import com.coc.docs.claudeusage.ClaudeUsageMyDashboardPoint;
import com.coc.docs.claudeusage.ClaudeUsageTotals;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
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

    /** A change to the caller's own profile. Name only — see SessionService.renameSelf. */
    public record UpdateProfileRequest(@NotBlank String displayName) {}

    /**
     * One device the caller is signed in on.
     *
     * <p>{@code id} is the opaque surrogate from V6, never the token hash:
     * the hash is what the server compares a presented token against, and it
     * has no business travelling to the client that presents one.
     */
    public record SessionResponse(
            UUID id,
            String userAgent,
            String ipAddress,
            Instant issuedAt,
            Instant expiresAt,
            /**
             * The browser and platform, as the server reads them —
             * "Chrome on Windows". Sent rather than left to the client so that
             * the list, the push banner and the audit entry all describe a
             * device the same way; they did not, when the frontend parsed the
             * user agent itself.
             */
            String device,
            /** Whether this session was the first from a device like it (ADR-027). */
            boolean newDevice,
            boolean current) {

        public static SessionResponse of(com.coc.docs.auth.SessionService.OwnSession session) {
            return new SessionResponse(
                    session.id(),
                    session.userAgent(),
                    session.ipAddress(),
                    session.issuedAt(),
                    session.expiresAt(),
                    session.device(),
                    session.newDevice(),
                    session.current());
        }
    }

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
            boolean authenticated,
            /** When the account was created — the profile screen's "Joined". */
            Instant createdAt,
            /** Most recent sign-in; null when no session row exists yet. */
            Instant lastSignInAt,
            /**
             * True while a {@code super_admin}-issued temporary password is
             * still in force. The frontend gates on this: it redirects to
             * Create New Password instead of the normal workspace until it
             * turns false (revision of ADR-017).
             */
            boolean mustChangePassword,
            /**
             * Whether this account holds a password credential at all
             * (ADR-030). "My Profile → Change Password" reads this to decide
             * whether to render a "Current Password" field — false for a
             * Google-only account, which has nothing to prove and is instead
             * offered "Set a password".
             */
            boolean hasPassword,
            /**
             * Every role held, not just the highest ({@code role} above) — an
             * account can hold more than one (ADR-016). Always a list, never
             * null, so the frontend can {@code .includes(...)} without a
             * guard; e.g. checking for the exact-membership {@code developer}
             * role (ADR-039) the way AX Renewal's confirm action does
             * server-side.
             */
            List<String> roles,
            /**
             * True only on the response to a sign-in call that itself just
             * cancelled a pending self-service deactivation (revision of
             * ADR-042) — always false from {@link #of}; set afterwards via
             * {@link #withDeactivationCancelled} by the three controller
             * methods that complete a sign-in ({@code /google},
             * {@code /login-password}, {@code /mfa/verify}). The frontend
             * uses it to tell the caller their scheduled deactivation was
             * just called off, rather than that being silently true.
             */
            boolean deactivationCancelled) {

        public static MeResponse of(CurrentUser user, Instant lastSignInAt) {
            return of(user.account(), lastSignInAt);
        }

        public static MeResponse of(Account account, Instant lastSignInAt) {
            return new MeResponse(
                    account.id(),
                    account.email(),
                    account.displayName(),
                    account.role().wire(),
                    account.avatarUrl(),
                    true,
                    account.createdAt(),
                    lastSignInAt,
                    account.mustChangePassword(),
                    account.hasPassword(),
                    account.roles().stream().map(AppRole::wire).sorted().toList(),
                    false);
        }

        /** A copy flagging that this same sign-in cancelled a pending self-service deactivation. */
        public MeResponse withDeactivationCancelled(boolean cancelled) {
            return new MeResponse(
                    id, email, displayName, role, avatarUrl, authenticated, createdAt, lastSignInAt,
                    mustChangePassword, hasPassword, roles, cancelled);
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
            return new MeResponse(null, null, null, null, null, false, null, null, false, false, List.of(), false);
        }
    }

    /** The Danger zone's chosen grace period (revision of ADR-042) — validated against {@code SessionService.DEACTIVATION_GRACE_PERIOD_DAYS_OPTIONS}. */
    public record DeactivateSelfRequest(@jakarta.validation.constraints.NotNull Integer graceDays) {}

    /** When a just-scheduled self-service deactivation will actually take effect, absent a sign-in before then. */
    public record DeactivateSelfResponse(Instant scheduledFor) {}

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
            List<String> roles,
            /**
             * True while this account is still using the temporary password a
             * {@code super_admin} invite issued it. Lets the administration
             * screen show "invited, awaiting first login" rather than looking
             * identical to an account that has never needed one.
             */
            boolean mustChangePassword,
            /**
             * The {@code picture} claim captured at this account's own last
             * Google/Keycloak sign-in (ADR-013), or null. Null is ordinary for
             * an account invited but not yet signed in, or one that only ever
             * uses the password path — same meaning as on {@link MeResponse},
             * just for someone else's account rather than the caller's own.
             */
            String avatarUrl) {

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
                            .toList(),
                    account.mustChangePassword(),
                    account.avatarUrl());
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
     *
     * <p>{@code roles} replaces the account's whole role set (ADR-016 —
     * "highest rank held" is already the effective-role rule; this is just
     * the API finally exposing the set {@code AccountService.setRoles}
     * always accepted). {@code role} is kept as the single-role convenience
     * most callers still use; when both are present, {@code roles} wins.
     */
    public record UpdateAccountRequest(
            String role,
            java.util.List<String> roles,
            Boolean active,
            String displayName,
            /**
             * The exact Name cell this account's owner appears under in the HRMS
             * attendance mail (V47). Absent leaves the link alone; a blank string
             * removes it. Decides whose attendance that person is shown, so it is
             * audited and never inferred -- see AccountService.linkHrmsName.
             */
            String hrmsName) {}

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
    /**
     * The role to admit a pending applicant at. Optional — absent means
     * {@code user}.
     */
    public record ApproveRequest(String role) {}

    public record CreateAccountRequest(
            @NotBlank @Email String email, @NotBlank String role) {}

    // -----------------------------------------------------------------------
    // Nav structure
    // -----------------------------------------------------------------------

    /** One sidebar/docs product, with its ordered sub-items. */
    public record NavProductResponse(
            String key, String label, String icon, String minRole, List<NavItemResponse> items) {

        public static NavProductResponse of(com.coc.docs.nav.NavProduct product) {
            return new NavProductResponse(
                    product.key(),
                    product.label(),
                    product.icon(),
                    product.minRole(),
                    product.items().stream().map(NavItemResponse::of).toList());
        }
    }

    /** One sub-item under a product. */
    public record NavItemResponse(String slug, String label) {

        public static NavItemResponse of(com.coc.docs.nav.NavItem item) {
            return new NavItemResponse(item.slug(), item.label());
        }
    }

    public record CreateNavProductRequest(@NotBlank String key, @NotBlank String label, @NotBlank String icon) {}

    public record UpdateNavProductRequest(@NotBlank String label, @NotBlank String icon) {}

    public record ReorderProductsRequest(@NotNull List<String> keys) {}

    public record CreateNavItemRequest(@NotNull String slug, @NotBlank String label) {}

    public record UpdateNavItemRequest(@NotBlank String label) {}

    public record ReorderItemsRequest(@NotNull List<String> slugs) {}

    // -----------------------------------------------------------------------
    // Notifications
    // -----------------------------------------------------------------------

    /**
     * The outcome of an on-demand digest send.
     *
     * <p>Counts, never addresses or names. The mail itself carries the page
     * titles and the editors; the confirmation a caller gets back does not need
     * to repeat them, and a recipient list echoed over HTTP would put personal
     * data in a place NFR-SEC-PII does not require it to be.
     *
     * @param sent whether a message was handed to the transport. False is a
     *     normal answer: nothing to report, nobody configured to receive it, or
     *     no SMTP host set — {@code reason} says which.
     */
    public record DigestResponse(
            LocalDate day, boolean sent, int pagesChanged, int sections, int recipients, String reason) {

        public static DigestResponse of(ActivityDigestService.DigestResult result) {
            return new DigestResponse(
                    result.day(),
                    result.sent(),
                    result.pagesChanged(),
                    result.sections(),
                    result.recipients(),
                    reasonFor(result.outcome()));
        }

        /**
         * Why nothing was sent, in words a caller can act on.
         *
         * <p>Taken from the outcome the service reported, never inferred from
         * the counts: a failed send of a day with no activity would otherwise
         * be reported as "no activity", which is the wrong thing to go and fix.
         */
        private static String reasonFor(ActivityDigestService.Outcome outcome) {
            return switch (outcome) {
                case SENT -> "sent";
                case NO_ACTIVITY -> "no page activity on this day";
                case NO_RECIPIENTS ->
                        "no recipients: app.notifications.recipients is empty and no active admin exists";
                case NOT_DELIVERED ->
                        "not delivered: no SMTP host is configured (spring.mail.host), or the relay "
                                + "refused it — see the server log";
            };
        }
    }

    /**
     * One line in the topbar's activity feed.
     *
     * <p>{@code occurredAt} is the instant, not a rendered "5m ago": the server
     * does not know the reader's clock or locale, and a cached response would
     * freeze a relative string while the absolute one stays correct. The client
     * formats it.
     *
     * <p>There is deliberately no {@code read} flag — see
     * {@code NotificationFeedService}'s note on why unread state would need a
     * table of its own.
     *
     * <p>{@code actionType}/{@code targetType}/{@code targetId} are the same
     * {@code audit_log} row {@code summary} already reads, exposed rather than
     * duplicated so a client can tell what kind of thing happened without
     * parsing the English sentence. {@code href}, unlike the other three, is
     * frequently null: see {@code NotificationFeedService.hrefFor} for when it
     * is populated and why it deliberately is not for every target type yet.
     *
     * <p>{@code actorAvatarUrl} is the actor's <em>current</em> picture, read
     * live rather than stored — null for no picture, a deleted account, or no
     * actor at all (an HRMS report arrival). No more sensitive than
     * {@code actorEmail}, which this response already carries.
     *
     * <p>// req: FR-OPS-001, NFR-SEC-PII
     */
    public record NotificationItem(
            String id,
            String actorEmail,
            String actorAvatarUrl,
            String summary,
            Instant occurredAt,
            String actionType,
            String targetType,
            UUID targetId,
            String href) {

        public static NotificationItem of(NotificationFeedService.FeedItem item) {
            return new NotificationItem(
                    item.id(),
                    item.actorEmail(),
                    item.actorAvatarUrl(),
                    item.summary(),
                    item.occurredAt(),
                    item.actionType(),
                    item.targetType(),
                    item.targetId(),
                    item.href());
        }
    }

    /**
     * One HRMS attendance report, as the popup that opens over the feed row
     * renders it.
     *
     * <p>Built from a mail fetched on the click and never stored — see
     * {@code HrmsNotificationService.report} and ADR-042. This is the only DTO
     * in this file carrying other people's personal data, and it is the only
     * one that must never end up in a cache header, a log line or a second
     * table.
     */
    public record HrmsMailContentResponse(
            String subject,
            LocalDate reportDate,
            String department,
            List<HrmsAttendanceRow> rows,
            List<HrmsMailDetail> details,
            String note,
            /**
             * Whether {@code rows} was narrowed to the caller's own attendance
             * (V47). False for an admin and above, who get the mail as sent.
             */
            boolean scopedToViewer,
            /**
             * Whether the caller's account is linked to a name in the mail.
             *
             * <p>Exists so the screen can tell two empty results apart, which
             * it could not before and got wrong: an unlinked account and a
             * report the parser failed on both arrive as zero rows, and showing
             * "HRMS may have changed its layout" for the first is a false
             * accusation against the sender. False only ever accompanies
             * {@code scopedToViewer}; an admin is never "unlinked".
             */
            boolean viewerLinked) {

        public static HrmsMailContentResponse of(HrmsNotificationService.ScopedReport scoped) {
            HrmsMailContent report = scoped.content();
            return new HrmsMailContentResponse(
                    report.subject(),
                    report.reportDate(),
                    report.department(),
                    report.rows().stream().map(HrmsAttendanceRow::of).toList(),
                    report.details().stream().map(HrmsMailDetail::of).toList(),
                    report.note(),
                    scoped.scopedToViewer(),
                    scoped.viewerLinked());
        }

        /**
         * The admin Attendance page's own shape: every department's mail as
         * sent, never narrowed per caller (that page is already floored at
         * {@code admin} in {@code SecurityConfig}, the same rank {@link
         * HrmsNotificationService#reportFor} itself stops scoping at) — so
         * {@code scopedToViewer}/{@code viewerLinked} carry the same constant
         * values an admin's own {@code ScopedReport} would.
         */
        public static HrmsMailContentResponse of(HrmsMailContent report) {
            return new HrmsMailContentResponse(
                    report.subject(),
                    report.reportDate(),
                    report.department(),
                    report.rows().stream().map(HrmsAttendanceRow::of).toList(),
                    report.details().stream().map(HrmsMailDetail::of).toList(),
                    report.note(),
                    false,
                    true);
        }
    }

    /**
     * One labelled field from an HRMS mail that is not an attendance report —
     * a leave or overtime request, an account created, a password reset. Those
     * are most of what the sender sends, and the popup rendered every one of
     * them as "could not be read" until this existed.
     */
    public record HrmsMailDetail(String label, String value) {

        static HrmsMailDetail of(HrmsMailContent.Detail detail) {
            return new HrmsMailDetail(detail.label(), detail.value());
        }
    }

    /** One person's line in the report above. */
    public record HrmsAttendanceRow(
            String name, HrmsAttendanceCheck yesterdayCheckOut, HrmsAttendanceCheck todayCheckIn) {

        static HrmsAttendanceRow of(HrmsMailContent.Row row) {
            return new HrmsAttendanceRow(
                    row.name(),
                    HrmsAttendanceCheck.of(row.yesterdayCheckOut()),
                    HrmsAttendanceCheck.of(row.todayCheckIn()));
        }
    }

    /**
     * One check: the scheduled time, what actually happened, and HRMS's word
     * for the difference. Strings throughout — see {@code
     * HrmsMailContent.Entry} for why these are never parsed to times.
     */
    public record HrmsAttendanceCheck(String scheduled, String actual, String status) {

        static HrmsAttendanceCheck of(HrmsMailContent.Entry entry) {
            return new HrmsAttendanceCheck(entry.scheduled(), entry.actual(), entry.status());
        }
    }

    // -----------------------------------------------------------------------
    // Audit / activity
    // -----------------------------------------------------------------------

    /** One audit entry, as the Overview "Recent Activity" panel sees it —
     *  the actor's own id and the target's uuid are dropped: neither means
     *  anything to that panel, and dropping them means one less field to
     *  remember not to widen later. */
    public record ActivityEntryResponse(
            String actorEmail, String actorAvatarUrl, String action, String targetType, JsonNode detail, Instant occurredAt) {

        public static ActivityEntryResponse of(com.coc.docs.audit.AuditEntry entry) {
            return new ActivityEntryResponse(
                    entry.actorEmail(),
                    entry.actorAvatarUrl(),
                    entry.action(),
                    entry.targetType(),
                    entry.detail(),
                    entry.occurredAt());
        }
    }

    // -----------------------------------------------------------------------
    // Tasks (ADR-033)
    // -----------------------------------------------------------------------

    public record CreateTaskRequest(@NotBlank String title, java.time.LocalDate dueDate) {}

    public record TaskResponse(UUID id, String title, java.time.LocalDate dueDate, String status, Instant createdAt) {

        public static TaskResponse of(com.coc.docs.tasks.Task task) {
            return new TaskResponse(task.id(), task.title(), task.dueDate(), task.status(), task.createdAt());
        }
    }

    /**
     * One of the caller's own notifications (ADR-034).
     *
     * <p>{@code payload} is sent as it is stored. The client renders the
     * sentence from {@code type} and these facts, so improving the wording
     * applies to everything already sent rather than only to what comes next —
     * which is why no rendered text is stored or returned here.
     *
     * <p>A client that does not recognise {@code type} — an older build, or
     * one after a type was retired — falls back to {@code payload.body}, which
     * every sender writes.
     */
    public record NotificationResponse(
            UUID id,
            String type,
            String targetId,
            com.fasterxml.jackson.databind.JsonNode payload,
            Instant createdAt,
            /** Null until the caller has seen it. */
            Instant readAt) {

        public static NotificationResponse of(com.coc.docs.notify.Notification n) {
            return new NotificationResponse(
                    n.id(), n.type(), n.targetId(), n.payload(), n.createdAt(), n.readAt());
        }
    }

    /**
     * The bell's whole state in one response.
     *
     * <p>The count travels with the list rather than coming from a second
     * endpoint: they are read together on every poll, and two round trips can
     * disagree with each other. {@code unreadCount} counts every unread
     * notification, not only those in {@code items} — the badge must not drop
     * because the list was capped.
     */
    public record NotificationInboxResponse(List<NotificationResponse> items, int unreadCount) {}

    /**
     * One of the caller's own notification preferences (ADR-042).
     *
     * <p>{@code enabled} always reflects the opt-out-by-absence default — the
     * client never has to know whether a stored row exists, only whether this
     * type is currently on.
     */
    public record NotificationPreferenceResponse(String type, String label, String description, boolean enabled) {

        public static NotificationPreferenceResponse of(com.coc.docs.notify.NotificationPreferenceService.Preference p) {
            return new NotificationPreferenceResponse(
                    p.type().key(), p.type().title(), p.type().body(), p.enabled());
        }
    }

    public record NotificationPreferenceListResponse(List<NotificationPreferenceResponse> items) {}

    public record UpdateNotificationPreferenceRequest(boolean enabled) {}

    // -----------------------------------------------------------------------
    // Claude Usage (ADR-035)
    // -----------------------------------------------------------------------

    /** One roster member, rolled up over the requested period. */
    public record ClaudeUsageMemberResponse(
            UUID id,
            String email,
            String displayName,
            String teamRole,
            String status,
            /** Null means "using the team default", never a stand-in for zero. */
            Long budgetOverrideTokens,
            long effectiveBudgetTokens,
            int budgetPct,
            /** {@code green}/{@code amber}/{@code red} — see {@link ClaudeUsageMemberUsage#budgetStatus()}. */
            String budgetStatus,
            List<String> modelsUsed,
            Instant lastActiveAt,
            long inputTokens,
            long outputTokens,
            long totalTokens,
            BigDecimal costUsd) {

        public static ClaudeUsageMemberResponse of(ClaudeUsageMemberUsage usage) {
            var member = usage.member();
            return new ClaudeUsageMemberResponse(
                    member.id(),
                    member.email(),
                    member.displayName(),
                    member.teamRole(),
                    member.status(),
                    member.budgetOverrideTokens(),
                    usage.effectiveBudgetTokens(),
                    usage.budgetPct(),
                    usage.budgetStatus(),
                    usage.modelsUsed(),
                    usage.lastActiveAt(),
                    usage.inputTokens(),
                    usage.outputTokens(),
                    usage.totalTokens(),
                    usage.costUsd());
        }
    }

    /** One day of the dashboard's stacked chart, keyed by member id for the per-teammate segments. */
    public record ClaudeUsageDailyPointResponse(LocalDate date, long total, Map<UUID, Long> byMember) {

        public static ClaudeUsageDailyPointResponse of(ClaudeUsageDailyPoint point) {
            return new ClaudeUsageDailyPointResponse(point.date(), point.total(), point.byMember());
        }
    }

    public record ClaudeUsageTotalsResponse(
            long inputTokens, long outputTokens, long cacheReadTokens, long cacheCreationTokens, long totalTokens, BigDecimal costUsd) {

        public static ClaudeUsageTotalsResponse of(ClaudeUsageTotals totals) {
            return new ClaudeUsageTotalsResponse(
                    totals.inputTokens(),
                    totals.outputTokens(),
                    totals.cacheReadTokens(),
                    totals.cacheCreationTokens(),
                    totals.totalTokens(),
                    totals.costUsd());
        }
    }

    /** Everything the Claude Usage screen renders for one period, in one response — read together, so two round trips cannot disagree. */
    public record ClaudeUsageDashboardResponse(
            String period,
            LocalDate from,
            LocalDate to,
            List<ClaudeUsageMemberResponse> members,
            List<ClaudeUsageDailyPointResponse> daily,
            ClaudeUsageTotalsResponse totals,
            int activeUsers,
            int nearLimit,
            Instant lastUpdated) {

        public static ClaudeUsageDashboardResponse of(ClaudeUsageDashboard dashboard) {
            return new ClaudeUsageDashboardResponse(
                    dashboard.period(),
                    dashboard.from(),
                    dashboard.to(),
                    dashboard.members().stream().map(ClaudeUsageMemberResponse::of).toList(),
                    dashboard.daily().stream().map(ClaudeUsageDailyPointResponse::of).toList(),
                    ClaudeUsageTotalsResponse.of(dashboard.totals()),
                    dashboard.activeUsers(),
                    dashboard.nearLimit(),
                    dashboard.lastUpdated());
        }
    }

    /** One day of the "My Claude Usage" personal history (ADR-052) — no per-member breakdown, unlike {@link ClaudeUsageDailyPointResponse}. */
    public record ClaudeUsageMyDashboardPointResponse(LocalDate date, long inputTokens, long outputTokens, long totalTokens) {

        public static ClaudeUsageMyDashboardPointResponse of(ClaudeUsageMyDashboardPoint point) {
            return new ClaudeUsageMyDashboardPointResponse(
                    point.date(), point.inputTokens(), point.outputTokens(), point.totalTokens());
        }
    }

    /**
     * Everything {@code GET /api/v1/claude-usage/me} renders — the caller's
     * own usage only, resolved server-side (ADR-052). {@code email} echoes
     * back who this is, for the header, never who was asked for.
     */
    public record ClaudeUsageMyDashboardResponse(
            String email,
            String displayName,
            LocalDate today,
            long todayInputTokens,
            long todayOutputTokens,
            long todayTotalTokens,
            List<ClaudeUsageMyDashboardPointResponse> daily,
            Instant lastUpdated,
            /** Null until the caller is on the roster (i.e. has never reported). */
            ClaudeUsageMemberResponse todayUsage,
            ClaudeUsageMyBreakdownResponse usage) {

        public static ClaudeUsageMyDashboardResponse of(ClaudeUsageMyDashboard dashboard) {
            return new ClaudeUsageMyDashboardResponse(
                    dashboard.email(),
                    dashboard.displayName(),
                    dashboard.today(),
                    dashboard.todayInputTokens(),
                    dashboard.todayOutputTokens(),
                    dashboard.todayTotalTokens(),
                    dashboard.daily().stream().map(ClaudeUsageMyDashboardPointResponse::of).toList(),
                    dashboard.lastUpdated(),
                    dashboard.todayUsage() == null ? null : ClaudeUsageMemberResponse.of(dashboard.todayUsage()),
                    ClaudeUsageMyBreakdownResponse.of(dashboard.usage()));
        }
    }

    /**
     * One model's usage over the personal screen's window (ADR-058) — the
     * same four categories Claude Code's own usage page shows, never folded
     * into input/output. No 1-hour split on the wire: it only ever affects
     * {@code costUsd}, which is already priced from it.
     */
    public record ClaudeUsageModelUsageResponse(
            String model,
            long inputTokens,
            long outputTokens,
            long cacheReadTokens,
            long cacheWriteTokens,
            BigDecimal costUsd) {

        public static ClaudeUsageModelUsageResponse of(ClaudeUsageMyDashboard.ModelUsage usage) {
            return new ClaudeUsageModelUsageResponse(
                    usage.model(), usage.inputTokens(), usage.outputTokens(), usage.cacheReadTokens(),
                    usage.cacheCreationTokens(), usage.costUsd());
        }
    }

    /** One (day, model) entry of the personal screen's "Recent Usage" table (ADR-058). */
    public record ClaudeUsageDayModelResponse(
            LocalDate date,
            String model,
            long inputTokens,
            long outputTokens,
            long cacheReadTokens,
            long cacheWriteTokens,
            BigDecimal costUsd) {

        public static ClaudeUsageDayModelResponse of(com.coc.docs.claudeusage.ClaudeUsageDailyModel row) {
            return new ClaudeUsageDayModelResponse(
                    row.usageDate(), row.model(), row.inputTokens(), row.outputTokens(), row.cacheReadTokens(),
                    row.cacheCreationTokens(), row.costUsd());
        }
    }

    /**
     * "Usage by Model" over the last {@code rangeDays} days (ADR-058).
     * {@code costUsd} is the sum of {@code byModel}'s own costs, so the
     * headline figure can never disagree with the cards under it.
     */
    public record ClaudeUsageMyBreakdownResponse(
            int rangeDays,
            LocalDate from,
            LocalDate to,
            long inputTokens,
            long outputTokens,
            long cacheReadTokens,
            long cacheWriteTokens,
            BigDecimal costUsd,
            BigDecimal previousCostUsd,
            List<ClaudeUsageModelUsageResponse> byModel,
            List<ClaudeUsageDayModelResponse> recent) {

        public static ClaudeUsageMyBreakdownResponse of(ClaudeUsageMyDashboard.ModelBreakdown breakdown) {
            var totals = breakdown.totals();
            return new ClaudeUsageMyBreakdownResponse(
                    breakdown.rangeDays(),
                    breakdown.from(),
                    breakdown.to(),
                    totals.inputTokens(),
                    totals.outputTokens(),
                    totals.cacheReadTokens(),
                    totals.cacheCreationTokens(),
                    totals.costUsd(),
                    breakdown.previousCostUsd(),
                    breakdown.byModel().stream().map(ClaudeUsageModelUsageResponse::of).toList(),
                    breakdown.recent().stream().map(ClaudeUsageDayModelResponse::of).toList());
        }
    }

    /**
     * A roster row on its own, with no usage figures — what a roster mutation
     * (invite/role/budget/remove) returns. Deliberately not {@link
     * ClaudeUsageMemberResponse}: that type is rolled up over a period this
     * request has no notion of, and zero-filling it would look like real data
     * for a period nobody asked about. The frontend re-fetches the dashboard
     * for the figures, the same way `users-roles`' page reloads its list after
     * a mutation rather than reading them off the mutation's own response.
     */
    public record ClaudeUsageMemberSummaryResponse(
            UUID id,
            String email,
            String displayName,
            String teamRole,
            String status,
            Long budgetOverrideTokens,
            Instant createdAt) {

        public static ClaudeUsageMemberSummaryResponse of(com.coc.docs.claudeusage.ClaudeUsageMember member) {
            return new ClaudeUsageMemberSummaryResponse(
                    member.id(),
                    member.email(),
                    member.displayName(),
                    member.teamRole(),
                    member.status(),
                    member.budgetOverrideTokens(),
                    member.createdAt());
        }
    }

    public record InviteClaudeUsageMemberRequest(
            @NotBlank @Email String email, String displayName, String teamRole) {}

    public record UpdateClaudeUsageMemberRoleRequest(@NotBlank String teamRole) {}

    /** The only field, so an explicit {@code null} and an absent field mean the same thing: clear the override. */
    public record UpdateClaudeUsageBudgetRequest(Long budgetOverrideTokens) {}

    /**
     * One model's token counts within a day's ingest — the breakdown real
     * per-model pricing needs (ADR-036, revision of ADR-035 decision 4).
     *
     * <p>{@code cacheCreation1h} is the part of {@code cacheCreation} written
     * with the 1-hour TTL (ADR-058). Absent from a reporter that predates it,
     * which Jackson reads as 0 — every write then prices as 5-minute, exactly
     * as before.
     */
    public record ClaudeUsageModelTokensRequest(
            long input, long output, long cacheRead, long cacheCreation, long cacheCreation1h) {}

    /**
     * The body {@code POST /api/ingest} accepts. No {@code cost_usd} — always
     * derived server-side, never trusted from the reporter.
     *
     * <p>{@code input}/{@code output}/{@code cacheRead}/{@code cacheCreation}
     * are the day's totals and are always required. {@code byModel} is the
     * optional per-model breakdown those totals are priced from (ADR-036); a
     * reporter that predates it can still send the flat totals and {@code
     * models} (names only, for display) and gets recorded correctly, just
     * priced at $0 rather than a guessed number — see {@code
     * ClaudeUsageService.recordUsage}.
     */
    public record ClaudeUsageIngestRequest(
            @NotBlank @Email String email,
            String displayName,
            @NotNull LocalDate date,
            long input,
            long output,
            long cacheRead,
            long cacheCreation,
            List<String> models,
            Map<String, ClaudeUsageModelTokensRequest> byModel) {}

    public record ClaudeUsageIngestResponse(String action) {}

    // -----------------------------------------------------------------------
    // Claude Usage ingest tokens (per-account /api/ingest credential, V35, ADR-047)
    // -----------------------------------------------------------------------

    public record ClaudeUsageIngestTokenCreateRequest(@NotBlank String label) {}

    /**
     * The one response that ever carries the raw bearer value — every other
     * read of this account's tokens ({@link ClaudeUsageIngestTokenResponse})
     * carries only the label and timestamps, never anything derived from it.
     *
     * <p>{@code ingestUrl} is the absolute {@code /api/ingest} URL of this
     * deployment ({@link PublicIngestUrl}) — what the personalized
     * {@code push-usage.mjs} embeds as {@code INGEST_URL}, so the frontend
     * never has to guess it from a build-time value.
     */
    public record ClaudeUsageIngestTokenCreateResponse(
            UUID id, String label, String token, Instant createdAt, String ingestUrl) {
        public static ClaudeUsageIngestTokenCreateResponse of(
                com.coc.docs.claudeusage.ClaudeUsageIngestTokenService.MintedToken minted, String ingestUrl) {
            return new ClaudeUsageIngestTokenCreateResponse(
                    minted.token().id(), minted.token().label(), minted.rawValue(), minted.token().createdAt(), ingestUrl);
        }
    }

    // -----------------------------------------------------------------------
    // claude-team-usage CLI browser login (V51, ADR-060)
    // -----------------------------------------------------------------------

    /** {@code codeChallenge} is the CLI's PKCE S256 challenge; {@code device} its machine name, shown on the consent screen. */
    public record ClaudeUsageCliAuthorizeRequest(@NotBlank String codeChallenge, String device) {}

    /** The raw one-time code, carried by the browser to the CLI's 127.0.0.1 listener. Never a token. */
    public record ClaudeUsageCliAuthorizeResponse(String code) {}

    public record ClaudeUsageCliTokenRequest(@NotBlank String code, @NotBlank String codeVerifier) {}

    /**
     * Everything the CLI saves, so nothing is ever typed: the personal token,
     * whose it is, and where to send usage ({@link PublicIngestUrl}).
     */
    public record ClaudeUsageCliTokenResponse(String token, String email, String displayName, String ingestUrl) {}

    public record ClaudeUsageIngestTokenResponse(UUID id, String label, Instant createdAt, Instant lastUsedAt) {
        public static ClaudeUsageIngestTokenResponse of(com.coc.docs.claudeusage.ClaudeUsageIngestToken token) {
            return new ClaudeUsageIngestTokenResponse(token.id(), token.label(), token.createdAt(), token.lastUsedAt());
        }
    }

    // -----------------------------------------------------------------------
    // Docs content meta (product docs pages' "About this document" panel)
    // -----------------------------------------------------------------------

    /** A sibling of the plain content-body response (`GET
     *  /api/v1/docs-content/{productKey}/{slug}`), not a replacement for it —
     *  that endpoint's response IS the `DocContent` shape verbatim on the
     *  frontend, and folding `updatedAt` into it would round-trip back into
     *  the stored body the next time an admin saves through the editor. */
    public record DocsContentMetaResponse(Instant updatedAt) {}

    /** Bulk existence check (see {@code DocsContentController#existence}) —
     *  the slugs, out of the ones asked about, that actually have a saved
     *  admin row. Order is whatever the query returned, not the caller's
     *  request order; the frontend only ever tests membership, never reads
     *  this positionally. */
    public record DocsContentExistenceResponse(List<String> savedSlugs) {}

    // -----------------------------------------------------------------------
    // AX Renewal (ADR-037)
    // -----------------------------------------------------------------------

    /**
     * What the workspace says about its analysis engine (ADR-043).
     *
     * <p>No path, no key, no model name -- only the two facts the UI needs.
     * A configured CLI path is a filesystem detail of somebody's machine and
     * has no business in an API response.
     *
     * @param connected whether Run analysis reaches a real model
     * @param readsSource whether that model can see the source, or only the
     *     stored dependency manifest
     */
    public record AxRenewalEngineResponse(boolean connected, boolean readsSource) {}

    /**
     * One turn typed into the AX Assistant's chat, plus the conversation the
     * browser is replaying with it (ADR-046).
     *
     * <p>{@code history} comes from the page because the server keeps none:
     * nothing about this conversation is stored, so there is nowhere else it
     * could come from. It is therefore caller-supplied and bounded here —
     * {@code 40} entries at 4,000 characters is well past any real
     * conversation and well short of a prompt somebody could use to make the
     * backend spawn work indefinitely. {@code LocalClaudeChat} trims further
     * to the last 20 turns.
     */
    public record AxRenewalChatRequest(
            @NotBlank @Size(max = 4_000) String message,
            @Size(max = 40) List<@Valid AxRenewalChatTurn> history,
            // req: ADR-055 — one caller's own legacy checkout for this one
            // question, in place of the server-side default that used to
            // exist; null/blank means manifest-only, same as omitting it.
            @Size(max = 1_024) String sourcePath) {}

    /** @param role {@code "you"} or {@code "assistant"}; anything else reads as the person. */
    public record AxRenewalChatTurn(@NotBlank @Size(max = 16) String role, @NotBlank @Size(max = 4_000) String text) {}

    /**
     * @param reply the model's own prose, unparsed
     * @param readSource whether it could see the source or only what this
     *     application stores — the panel says which, rather than letting
     *     somebody assume the code was read
     */
    public record AxRenewalChatResponse(String reply, boolean readSource) {}

    public record CreateAxRenewalProjectRequest(@NotBlank String name, String description) {}

    // filename is nullable, not @NotBlank: a folder with no recognized
    // dependency manifest is a legitimate project to attach (e.g. a static
    // site), not a rejected request — see AxRenewalService#attachManifest.
    // stats is the browser's own computed facts about the picked folder
    // (file count, size, extensions, lockfile/secrets/build-output checks,
    // largest file) — optional, since a manual re-attach without picking a
    // folder again has nothing new to report.
    public record AttachManifestRequest(String filename, String content, JsonNode stats) {}

    public record AxRenewalStackLayerRequest(@NotBlank String layerKey, String oldTarget, String newTarget) {}

    public record UpdateStackMoveRequest(@NotNull List<AxRenewalStackLayerRequest> layers) {}

    public record UpdateArchitectureRequest(String summary, JsonNode diagram) {}

    public record AxRenewalPageMappingRequest(String oldPath, String newPath, String notes) {}

    public record UpdatePagesRequest(@NotNull List<AxRenewalPageMappingRequest> pages) {}

    public record AxRenewalDbChangeRequest(
            @NotBlank String changeType, @NotBlank String tableName, JsonNode detail, String rationale) {}

    public record UpdateDatabasePlanRequest(@NotNull List<AxRenewalDbChangeRequest> changes) {}

    public record ConnectDatabaseRequest(
            String engine, String host, Integer port, String databaseName, String schemaName) {}

    public record SetDestinationRequest(
            @NotBlank String kind, @NotBlank String target, String branch, String notes) {}

    public record AddAxRenewalCommentRequest(@NotBlank String body) {}

    public record AxRenewalProjectResponse(
            UUID id,
            String name,
            String description,
            String status,
            String manifestFilename,
            Instant manifestAttachedAt,
            JsonNode attachStats,
            Instant createdAt,
            Instant updatedAt) {

        public static AxRenewalProjectResponse of(AxRenewalProject project) {
            return new AxRenewalProjectResponse(
                    project.id(),
                    project.name(),
                    project.description(),
                    project.status(),
                    project.manifestFilename(),
                    project.manifestAttachedAt(),
                    project.attachStats(),
                    project.createdAt(),
                    project.updatedAt());
        }
    }

    public record AxRenewalAnalysisResponse(
            JsonNode stackDetected,
            JsonNode findings,
            JsonNode pagesDetected,
            JsonNode indexed,
            JsonNode runningOnToday,
            JsonNode fileLayout,
            JsonNode moduleCoupling,
            JsonNode recommendedStackMove,
            boolean modelConnected,
            Instant analyzedAt) {

        public static AxRenewalAnalysisResponse of(AxRenewalAnalysis analysis) {
            return new AxRenewalAnalysisResponse(
                    analysis.stackDetected(),
                    analysis.findings(),
                    analysis.pagesDetected(),
                    analysis.indexed(),
                    analysis.runningOnToday(),
                    analysis.fileLayout(),
                    analysis.moduleCoupling(),
                    analysis.recommendedStackMove(),
                    analysis.modelConnected(),
                    analysis.analyzedAt());
        }
    }

    public record AxRenewalStackLayerResponse(String layerKey, String oldTarget, String newTarget, int position) {

        public static AxRenewalStackLayerResponse of(AxRenewalStackLayer layer) {
            return new AxRenewalStackLayerResponse(
                    layer.layerKey(), layer.oldTarget(), layer.newTarget(), layer.position());
        }
    }

    public record AxRenewalArchitectureResponse(String summary, JsonNode diagram, Instant updatedAt) {

        public static AxRenewalArchitectureResponse of(AxRenewalArchitecturePlan architecture) {
            return new AxRenewalArchitectureResponse(
                    architecture.summary(), architecture.diagram(), architecture.updatedAt());
        }
    }

    public record AxRenewalPageMappingResponse(String oldPath, String newPath, String notes, int position) {

        public static AxRenewalPageMappingResponse of(AxRenewalPageMapping mapping) {
            return new AxRenewalPageMappingResponse(
                    mapping.oldPath(), mapping.newPath(), mapping.notes(), mapping.position());
        }
    }

    public record AxRenewalDbChangeResponse(
            String changeType, String tableName, JsonNode detail, String rationale, int position) {

        public static AxRenewalDbChangeResponse of(AxRenewalDbChange change) {
            return new AxRenewalDbChangeResponse(
                    change.changeType(), change.tableName(), change.detail(), change.rationale(), change.position());
        }
    }

    public record AxRenewalDbConnectionResponse(
            String engine, String host, Integer port, String databaseName, String schemaName, Instant connectedAt) {

        public static AxRenewalDbConnectionResponse of(AxRenewalDbConnection connection) {
            return new AxRenewalDbConnectionResponse(
                    connection.engine(),
                    connection.host(),
                    connection.port(),
                    connection.databaseName(),
                    connection.schemaName(),
                    connection.connectedAt());
        }
    }

    public record AxRenewalDestinationResponse(String kind, String target, String branch, String notes) {

        public static AxRenewalDestinationResponse of(AxRenewalDestination destination) {
            return new AxRenewalDestinationResponse(
                    destination.kind(), destination.target(), destination.branch(), destination.notes());
        }
    }

    public record AxRenewalConfirmationResponse(
            String section, Instant confirmedAt, UUID confirmedBy, String confirmedVia) {

        public static AxRenewalConfirmationResponse of(AxRenewalConfirmation confirmation) {
            return new AxRenewalConfirmationResponse(
                    confirmation.section().wire(),
                    confirmation.confirmedAt(),
                    confirmation.confirmedBy(),
                    confirmation.confirmedVia());
        }
    }

    public record AxRenewalImplementationRunResponse(
            UUID id,
            String status,
            String runKind,
            String instruction,
            JsonNode beforeReport,
            JsonNode afterReport,
            Instant startedAt,
            Instant completedAt,
            String failureReason) {

        public static AxRenewalImplementationRunResponse of(AxRenewalImplementationRun run) {
            return new AxRenewalImplementationRunResponse(
                    run.id(),
                    run.status(),
                    run.runKind(),
                    run.instruction(),
                    run.beforeReport(),
                    run.afterReport(),
                    run.startedAt(),
                    run.completedAt(),
                    run.failureReason());
        }
    }

    /** @param description a person's own free-text UI change request — colors, layout, fonts, spacing, component styles — against the renewed project's own UI (ADR-062 Phase 2). */
    public record StartUiChangeRequest(@NotBlank @Size(max = 4_000) String description) {}

    /**
     * @param executed false when nothing was actually run (no staged
     *     changes, no {@code test} script, a copy/spawn failure, or a
     *     timeout) — {@code note} says which, honestly. True means {@code
     *     exitCode}/{@code output} are the real result, whatever it was — a
     *     nonzero exit is a legitimate answer, not a reason to report false.
     */
    public record AxRenewalTestRunResponse(boolean executed, Integer exitCode, List<String> output, String note) {

        public static AxRenewalTestRunResponse of(com.coc.docs.axrenewal.AxRenewalTestRunner.Result result) {
            return new AxRenewalTestRunResponse(result.executed(), result.exitCode(), result.output(), result.note());
        }
    }

    /**
     * Live status of the Run Locally dev server for one project (ADR-048) —
     * in-memory only, never persisted: a live OS process cannot survive a
     * backend restart regardless of what a row would claim. {@code log} is
     * the process's own real combined stdout/stderr, a rolling window, never
     * a summary. {@code url} is null until the process's own output
     * announces it is ready — never a hardcoded guess at a port.
     */
    public record AxRenewalRunLocallyStatusResponse(String status, List<String> log, String url, Instant startedAt) {

        public static AxRenewalRunLocallyStatusResponse of(AxRenewalLocalRunner.Status status) {
            return new AxRenewalRunLocallyStatusResponse(
                    status.state(), status.log(), status.url(), status.startedAt());
        }
    }

    /** One file a Generate run actually found different — real before/after content, never a computed patch format (ADR-044). */
    public record AxRenewalGeneratedChangeResponse(
            String path, String changeKind, String beforeContent, String afterContent) {

        public static AxRenewalGeneratedChangeResponse of(AxRenewalGeneratedChange change) {
            return new AxRenewalGeneratedChangeResponse(
                    change.path(), change.changeKind(), change.beforeContent(), change.afterContent());
        }
    }

    public record AxRenewalCommentResponse(UUID id, UUID authorId, String authorName, String body, Instant createdAt) {

        public static AxRenewalCommentResponse of(AxRenewalComment comment) {
            return new AxRenewalCommentResponse(
                    comment.id(), comment.authorId(), comment.authorName(), comment.body(), comment.createdAt());
        }
    }

    /**
     * Everything one AX Renewal project's screen renders, in one response
     * (ADR-037). {@code confirmations} is keyed by section wire form (e.g.
     * {@code "stack_move"}) — a section absent from it is unconfirmed.
     */
    public record AxRenewalProjectDetailResponse(
            AxRenewalProjectResponse project,
            AxRenewalAnalysisResponse analysis,
            List<AxRenewalStackLayerResponse> stackLayers,
            AxRenewalArchitectureResponse architecture,
            List<AxRenewalPageMappingResponse> pageMappings,
            List<AxRenewalDbChangeResponse> dbChanges,
            AxRenewalDbConnectionResponse dbConnection,
            AxRenewalDestinationResponse destination,
            Map<String, AxRenewalConfirmationResponse> confirmations,
            AxRenewalImplementationRunResponse latestImplementationRun,
            List<AxRenewalCommentResponse> comments) {

        public static AxRenewalProjectDetailResponse of(AxRenewalProjectDetail detail) {
            Map<String, AxRenewalConfirmationResponse> confirmations = new java.util.LinkedHashMap<>();
            detail.confirmations()
                    .forEach((section, confirmation) ->
                            confirmations.put(section.wire(), AxRenewalConfirmationResponse.of(confirmation)));

            return new AxRenewalProjectDetailResponse(
                    AxRenewalProjectResponse.of(detail.project()),
                    detail.analysis() == null ? null : AxRenewalAnalysisResponse.of(detail.analysis()),
                    detail.stackLayers().stream().map(AxRenewalStackLayerResponse::of).toList(),
                    detail.architecture() == null ? null : AxRenewalArchitectureResponse.of(detail.architecture()),
                    detail.pageMappings().stream().map(AxRenewalPageMappingResponse::of).toList(),
                    detail.dbChanges().stream().map(AxRenewalDbChangeResponse::of).toList(),
                    detail.dbConnection() == null ? null : AxRenewalDbConnectionResponse.of(detail.dbConnection()),
                    detail.destination() == null ? null : AxRenewalDestinationResponse.of(detail.destination()),
                    confirmations,
                    detail.latestImplementationRun() == null
                            ? null
                            : AxRenewalImplementationRunResponse.of(detail.latestImplementationRun()),
                    detail.comments().stream().map(AxRenewalCommentResponse::of).toList());
        }
    }
}
