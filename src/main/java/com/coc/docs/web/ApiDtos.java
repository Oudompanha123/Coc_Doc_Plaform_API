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
import com.coc.docs.notify.ActivityDigestService;
import com.coc.docs.notify.NotificationFeedService;
import com.coc.docs.page.PageVersionRepository;
import com.coc.docs.section.Section;
import com.coc.docs.claudeusage.ClaudeUsageDailyPoint;
import com.coc.docs.claudeusage.ClaudeUsageDashboard;
import com.coc.docs.claudeusage.ClaudeUsageMemberUsage;
import com.coc.docs.claudeusage.ClaudeUsageTotals;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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
            boolean hasPassword) {

        public static MeResponse of(CurrentUser user, Instant lastSignInAt) {
            return new MeResponse(
                    user.id(),
                    user.email(),
                    user.account().displayName(),
                    user.role().wire(),
                    user.account().avatarUrl(),
                    true,
                    user.account().createdAt(),
                    lastSignInAt,
                    user.account().mustChangePassword(),
                    user.account().hasPassword());
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
            return new MeResponse(null, null, null, null, null, false, null, null, false, false);
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
     * <p>// req: FR-OPS-001, NFR-SEC-PII
     */
    public record NotificationItem(String id, String actorEmail, String summary, Instant occurredAt) {

        public static NotificationItem of(NotificationFeedService.FeedItem item) {
            return new NotificationItem(item.id(), item.actorEmail(), item.summary(), item.occurredAt());
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
            String actorEmail, String action, String targetType, JsonNode detail, Instant occurredAt) {

        public static ActivityEntryResponse of(com.coc.docs.audit.AuditEntry entry) {
            return new ActivityEntryResponse(
                    entry.actorEmail(), entry.action(), entry.targetType(), entry.detail(), entry.occurredAt());
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
     */
    public record ClaudeUsageModelTokensRequest(long input, long output, long cacheRead, long cacheCreation) {}

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
    // Docs content meta (product docs pages' "About this document" panel)
    // -----------------------------------------------------------------------

    /** A sibling of the plain content-body response (`GET
     *  /api/v1/docs-content/{productKey}/{slug}`), not a replacement for it —
     *  that endpoint's response IS the `DocContent` shape verbatim on the
     *  frontend, and folding `updatedAt` into it would round-trip back into
     *  the stored body the next time an admin saves through the editor. */
    public record DocsContentMetaResponse(Instant updatedAt) {}
}
