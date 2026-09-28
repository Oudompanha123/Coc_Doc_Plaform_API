package com.coc.docs.notify;

import com.coc.docs.account.AppRole;
import com.coc.docs.audit.AuditAction;
import com.coc.docs.audit.AuditEntry;
import com.coc.docs.audit.AuditRepository;
import com.coc.docs.auth.CurrentUser;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;

/**
 * The activity feed behind the topbar's message icon.
 *
 * <p>Derived from {@code audit_log} rather than from a table of its own. The
 * events worth telling someone about are exactly the events already recorded
 * there, and a second table would be a second place for them to be written,
 * kept in step, and forgotten. The cost is that there is no per-recipient read
 * state — see {@link #WHY_NO_UNREAD} — which is the trade this deliberately
 * accepts for now.
 *
 * <p><b>Scope is decided here, from the role on the session, and nowhere
 * else.</b> A {@code super_admin} sees everyone's activity; every other role
 * sees only their own. That is not a display preference: for a non-super_admin
 * the query never selects another account's rows, so no other person's email
 * or name is in the response to redact. This keeps the rule that account
 * identities reach only a {@code super_admin} (NFR-SEC-PII) true of this route
 * the same way {@code SecurityConfig} keeps it true of
 * {@code /api/v1/admin/accounts/**}.
 *
 * <p>// req: FR-OPS-001, FR-AUTH-008, NFR-SEC-PII, NFR-OPS-AUDIT
 */
@Service
public class NotificationFeedService {

    /**
     * Why an item has no read/unread flag: {@code audit_log} records that a
     * thing happened, not that a person saw it, and it is append-only by
     * convention ({@code revoke update, delete}). Marking one read would mean
     * writing somewhere else anyway. The badge therefore counts recent items,
     * not unread ones, and the UI must not claim otherwise.
     */
    static final String WHY_NO_UNREAD = "audit_log is append-only; read state would need its own table";

    /** Hard ceiling on one page of feed, whatever the caller asks for. */
    private static final int MAX_LIMIT = 50;

    private static final int DEFAULT_LIMIT = 20;

    /**
     * Sign-in and sign-out are excluded, and only these two.
     *
     * <p>They are 250 of the ~450 rows in a typical database here and they tell
     * the reader nothing they did not already know — a feed that is four-fifths
     * "you signed in" buries the approval or role change that actually needed
     * attention. Everything else, {@code auth.rejected} included, is kept:
     * a refused sign-in on your own account is precisely the kind of thing a
     * notification exists for.
     */
    static final Set<String> HIDDEN_ACTIONS = Set.of(AuditAction.AUTH_SIGN_IN, AuditAction.AUTH_SIGN_OUT);

    /**
     * Namespace for HRMS ids in the feed.
     *
     * <p>Feed ids are opaque strings to the client, but they come from two
     * tables now, and {@code audit_log.id} 41 and {@code hrms_notification.id}
     * 41 are both plausible on the same day. Without a prefix they would
     * collide in any client keyed on the id — React's list reconciliation, for
     * one — and the symptom would be a notification that flickers or refuses
     * to re-render rather than anything that looks like a backend bug.
     */
    private static final String HRMS_ID_PREFIX = "hrms:";

    private final AuditRepository audit;
    private final HrmsNotificationService hrms;

    public NotificationFeedService(AuditRepository audit, HrmsNotificationService hrms) {
        this.audit = audit;
        this.hrms = hrms;
    }

    /**
     * One line in the feed. {@code actorEmail} is null when the actor was the
     * system.
     *
     * <p>{@code actionType}/{@code targetType}/{@code targetId} are the same
     * {@code audit_log} columns {@code summary} was already built from —
     * carried through rather than duplicated, per {@code audit_log}'s existing
     * {@code target_type}/{@code target_id} columns (no new storage). {@code
     * href} is the one field this class computes rather than copies: where in
     * this app that target actually opens, decided here so the client never
     * has to guess a route from an id, and gated by role so it is never
     * offered to a caller who could not reach it anyway (NFR-SEC-PII). Null on
     * every field a caller cannot act on — including every {@code page}
     * target today, because no page editor exists in this frontend yet (see
     * {@link #hrefFor}).
     *
     * <p>{@code actorAvatarUrl} is likewise carried straight through from
     * {@code AuditEntry} (itself a live join, not a stored column — see that
     * record's javadoc); null whenever the actor has no picture, has been
     * deleted, or the row has no actor at all (an HRMS arrival).
     */
    public record FeedItem(
            String id,
            String actorEmail,
            String actorAvatarUrl,
            String summary,
            Instant occurredAt,
            String actionType,
            String targetType,
            UUID targetId,
            String href) {}

    /**
     * The feed for {@code caller}, newest first.
     *
     * @param limit how many to return; clamped to {@value #MAX_LIMIT}, and a
     *     non-positive value means the default rather than an error, because a
     *     paging bug in a caller should degrade to a short list, not a 400 on a
     *     decoration
     */
    public List<FeedItem> feedFor(CurrentUser caller, int limit) {
        int capped = Math.min(limit <= 0 ? DEFAULT_LIMIT : limit, MAX_LIMIT);

        // Over-fetch, because the hidden actions are filtered after the query
        // rather than in it: a caller whose last 20 rows are all sign-ins would
        // otherwise get an empty feed. Bounded so this cannot become a scan.
        int fetch = Math.min(capped * 4, MAX_LIMIT * 4);

        List<AuditEntry> rows = caller.role() == AppRole.SUPER_ADMIN
                ? audit.recent(fetch)
                : audit.recentForActor(caller.id(), fetch);

        Stream<FeedItem> activity = rows.stream()
                .filter(entry -> !HIDDEN_ACTIONS.contains(entry.action()))
                .map(entry -> toItem(entry, caller));

        // Merged and re-sorted rather than appended: the two sources are each
        // newest-first on their own, and concatenating them would put a
        // three-day-old report above this morning's page edit.
        return Stream.concat(activity, hrmsItemsFor(caller, capped))
                .sorted(Comparator.comparing(FeedItem::occurredAt).reversed())
                .limit(capped)
                .toList();
    }

    /**
     * HRMS report arrivals, for every signed-in role.
     *
     * <p><b>This used to be {@code admin} and above, and it used to say that
     * if the content ever named people the floor would have to move up to
     * {@code super_admin}.</b> The content now does name people — opening a
     * row shows the attendance table (ADR-042) — and the floor moved the other
     * way, to every role, on the PM's decision. The rule that predicted this is
     * left here rather than quietly deleted, because the next person to read
     * it should see that it was overruled and not that it never existed.
     *
     * <p>What that means in practice: any signed-in user can see that a report
     * arrived and can open it. ADR-042 records who decided that and on what
     * grounds; it is a business judgement about one company's own attendance
     * data, not a defect, and it is not this class's to re-litigate. What this
     * class still owes is that the decision is visible — hence this paragraph
     * and the ADR reference, rather than a silently deleted {@code if}.
     *
     * <p>Not filtered by actor the way audit rows are: there is no actor.
     * Nobody in this system did it.
     */
    private Stream<FeedItem> hrmsItemsFor(CurrentUser caller, int limit) {
        return hrms.recent(limit).stream().map(NotificationFeedService::toItem);
    }

    /**
     * An HRMS arrival as a feed line.
     *
     * <p>{@code actorEmail} is null, which the {@link FeedItem} contract
     * already defines as "the actor was the system". That is the closest true
     * statement available: a different company's server sent an email.
     */
    private static FeedItem toItem(HrmsNotification notification) {
        return new FeedItem(
                HRMS_ID_PREFIX + notification.id(),
                null,
                null,
                notification.summary(),
                notification.receivedAt(),
                null,
                null,
                null,
                null);
    }

    private static FeedItem toItem(AuditEntry entry, CurrentUser caller) {
        return new FeedItem(
                Long.toString(entry.id()),
                entry.actorEmail(),
                entry.actorAvatarUrl(),
                summaryFor(entry),
                entry.occurredAt(),
                entry.action(),
                entry.targetType(),
                entry.targetId(),
                hrefFor(entry, caller));
    }

    /**
     * Where {@code entry}'s target actually opens in this app, or null when
     * there is nowhere to send the reader — either because this deployment
     * has no destination for that target type yet, or because {@code caller}
     * could not open it even if there were one.
     *
     * <p><b>Only {@code account} resolves today.</b> {@code page} carries a
     * real {@code target_id} (the {@code page}/{@code page_version} tables and
     * {@code AdminContentController} both exist), but no frontend route opens
     * one — there is no page editor and no reader page keyed by page id
     * anywhere in this codebase yet, confirmed by grep. Returning a link to a
     * page that does not exist would be worse than returning none, so a
     * {@code page} target is deliberately left with no {@code href} until an
     * editor exists to send someone to.
     *
     * <p>Gated on role here, not left to the destination to refuse, for the
     * same reason {@code SecurityConfig} puts {@code /users-roles} and
     * {@code /api/v1/admin/accounts/**} at {@code super_admin}: an account
     * target is exactly the class of thing NFR-SEC-PII exists to keep off a
     * lower-privileged screen, and a caller who could never reach
     * {@code /users-roles} should not be handed a link to it, even a dead one.
     *
     * <p>Does not check that the target still exists — an account or page can
     * be deleted after the audit row was written and before anyone reads this
     * feed, and a query per feed row to guard against that race would not
     * even close it. The destination itself is what has to answer "not found"
     * gracefully; see {@code UsersRolesPage}'s {@code highlight} handling.
     */
    private static String hrefFor(AuditEntry entry, CurrentUser caller) {
        if (AuditAction.TARGET_ACCOUNT.equals(entry.targetType())
                && entry.targetId() != null
                && caller.isAtLeast(AppRole.SUPER_ADMIN)) {
            return "/users-roles?highlight=" + entry.targetId();
        }
        return null;
    }

    /**
     * The action, in words.
     *
     * <p>A switch rather than a prettified {@code action} string: "account.
     * role_change" with the dot swapped for a space reads like a log line, and
     * this is shown to people who never see log lines. An action with no case
     * here falls back to the raw key, which is ugly on purpose — it is meant to
     * be noticed and given a sentence, not to pass unremarked.
     *
     * <p><b>"Noticed" now means at build time.</b> It did not, and nineteen
     * actions accumulated without one — including
     * {@code auth.password_change_complete}, which is raised by the everyday
     * act of changing your own password and so appeared in the topbar, in that
     * spelling, to the person who had just done it. The fallback stays, because
     * a build older than an action should still render something true rather
     * than a blank row, but {@code NotificationFeedServiceTest} now fails when
     * a visible action reaches it. A default case is a reasonable thing to have
     * and an unreasonable thing to rely on.
     */
    private static String summaryFor(AuditEntry entry) {
        return switch (entry.action()) {
            case AuditAction.PAGE_PUBLISH -> "Published a page";
            case AuditAction.PAGE_DRAFT_SAVE -> "Saved a draft";
            case AuditAction.PAGE_CREATE -> "Created a page";
            case AuditAction.PAGE_RENAME -> "Renamed a page";
            case AuditAction.PAGE_DELETE -> "Deleted a page";
            case AuditAction.PAGE_REORDER -> "Reordered pages";
            case AuditAction.SECTION_DB_BACKED -> "Changed a section's content source";
            case AuditAction.ACCOUNT_CREATE -> "Created an account";
            case AuditAction.ACCOUNT_ROLE_CHANGE -> "Changed an account's role";
            case AuditAction.ACCOUNT_DEACTIVATE -> "Deactivated an account";
            case AuditAction.ACCOUNT_DEACTIVATE_SCHEDULE -> "Scheduled an account to deactivate";
            case AuditAction.ACCOUNT_DEACTIVATE_CANCEL -> "Cancelled a scheduled deactivation";
            case AuditAction.ACCOUNT_REACTIVATE -> "Reactivated an account";
            case AuditAction.ACCOUNT_DELETE -> "Deleted an account";
            case AuditAction.ACCOUNT_REGISTER -> "Registered an account";
            case AuditAction.ACCOUNT_REGISTER_OTP_REQUEST -> "Requested a verification code";
            case AuditAction.ACCOUNT_APPROVE -> "Approved a registration";
            case AuditAction.ACCOUNT_REJECT -> "Rejected a registration";
            case AuditAction.AUTH_REJECTED -> "A sign-in attempt was refused";
            case AuditAction.PASSWORD_RESET_REQUEST -> "Requested a password reset";
            case AuditAction.PASSWORD_RESET_COMPLETE -> "Reset a password";
            case AuditAction.PASSWORD_CHANGE_COMPLETE -> "Changed a password";
            case AuditAction.MFA_ENABLED -> "Turned on two-factor authentication";
            case AuditAction.MFA_DISABLED -> "Turned off two-factor authentication";
            case AuditAction.NOTIFICATION_DIGEST_SEND -> "Sent the activity digest";
            case AuditAction.DOCS_CONTENT_SAVE -> "Saved a documentation section";
            case AuditAction.NAV_PRODUCT_CREATE -> "Added a product";
            case AuditAction.NAV_PRODUCT_DELETE -> "Removed a product";
            case AuditAction.NAV_ITEM_CREATE -> "Added a navigation item";
            case AuditAction.NAV_ITEM_DELETE -> "Removed a navigation item";
            case AuditAction.CLAUDE_USAGE_MEMBER_INVITE -> "Invited someone to Claude Usage";
            case AuditAction.CLAUDE_USAGE_MEMBER_ROLE_CHANGE -> "Changed a Claude Usage member's role";
            case AuditAction.CLAUDE_USAGE_MEMBER_BUDGET_CHANGE -> "Changed a Claude Usage member's budget";
            case AuditAction.CLAUDE_USAGE_MEMBER_REMOVE -> "Removed a Claude Usage member";
            // Added at merge time: feature/claude-usage-ingest-tokens introduced
            // both actions without a sentence, which the "every visible action
            // has one" guard catches — the feed would print the raw constant.
            // Deliberately says nothing about the token itself; a feed line is
            // read by anyone with the roster, and which token is not their business.
            case AuditAction.CLAUDE_USAGE_INGEST_TOKEN_CREATE -> "Created a usage reporting token";
            case AuditAction.CLAUDE_USAGE_INGEST_TOKEN_REVOKE -> "Revoked a usage reporting token";
            case AuditAction.AX_RENEWAL_PROJECT_CREATE -> "Started an AX Renewal project";
            case AuditAction.AX_RENEWAL_PROJECT_DELETE -> "Deleted an AX Renewal project";
            case AuditAction.AX_RENEWAL_MANIFEST_ATTACH -> "Attached a codebase manifest";
            case AuditAction.AX_RENEWAL_ANALYSIS_RUN -> "Analysed a codebase";
            case AuditAction.AX_RENEWAL_STACK_MOVE_EDIT -> "Changed a stack move target";
            case AuditAction.AX_RENEWAL_ARCHITECTURE_EDIT -> "Edited a renewal architecture plan";
            case AuditAction.AX_RENEWAL_PAGES_EDIT -> "Edited a page mapping";
            case AuditAction.AX_RENEWAL_DATABASE_PLAN_EDIT -> "Edited a database change plan";
            case AuditAction.AX_RENEWAL_DATABASE_CONNECT -> "Connected a database to a renewal project";
            case AuditAction.AX_RENEWAL_DESTINATION_SET -> "Set where renewed code is written";
            case AuditAction.AX_RENEWAL_SECTION_CONFIRM -> "Confirmed a renewal plan section";
            case AuditAction.AX_RENEWAL_IMPLEMENTATION_START -> "Started a renewal implementation";
            // Added here at merge time: feature/ax-renewal-generate-and-apply
            // introduced these two actions without a sentence each, which the
            // "every visible action has one" guard catches — an action with no
            // wording reads as its own raw constant in the activity feed.
            case AuditAction.AX_RENEWAL_IMPLEMENTATION_APPROVE -> "Approved generated code changes";
            case AuditAction.AX_RENEWAL_IMPLEMENTATION_REJECT -> "Rejected generated code changes";
            case AuditAction.AX_RENEWAL_IMPLEMENTATION_CANCEL -> "Cancelled a running renewal implementation";
            case AuditAction.AX_RENEWAL_IMPLEMENTATION_REPLAY -> "Reused a previous rebuild's exact result";
            case AuditAction.AX_RENEWAL_COMMENT_ADD -> "Commented on a renewal project";
            case AuditAction.AX_RENEWAL_COMMENT_DELETE -> "Removed a comment from a renewal project";
            case AuditAction.AX_RENEWAL_RUN_LOCALLY_START -> "Started a local run of a renewed project";
            case AuditAction.AX_RENEWAL_RUN_LOCALLY_STOP -> "Stopped a local run of a renewed project";
            case AuditAction.ACCOUNT_CAPABILITY_GRANT -> "Granted a capability";
            case AuditAction.ACCOUNT_CAPABILITY_REVOKE -> "Revoked a capability";
            default -> entry.action();
        };
    }
}
