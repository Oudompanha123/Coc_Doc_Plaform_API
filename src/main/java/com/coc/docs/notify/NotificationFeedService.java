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

    /** One line in the feed. {@code actorEmail} is null when the actor was the system. */
    public record FeedItem(String id, String actorEmail, String summary, Instant occurredAt) {}

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
                .map(NotificationFeedService::toItem);

        // Merged and re-sorted rather than appended: the two sources are each
        // newest-first on their own, and concatenating them would put a
        // three-day-old report above this morning's page edit.
        return Stream.concat(activity, hrmsItemsFor(caller, capped))
                .sorted(Comparator.comparing(FeedItem::occurredAt).reversed())
                .limit(capped)
                .toList();
    }

    /**
     * HRMS report arrivals, for the roles that should see them.
     *
     * <p><b>{@code admin} and above.</b> These are operational notices about a
     * mailbox the platform watches, not editorial activity, and an editor has
     * no use for them — the same reasoning that puts the digest route at a
     * higher floor than the rest of {@code /admin}. It is not a confidentiality
     * boundary: a subject line says a report arrived on a date and names
     * nobody, which is the whole reason the body was never stored
     * ({@code V21__hrms_notification.sql}). If that ever stops being true, this
     * rule is no longer sufficient and the floor has to move to
     * {@code super_admin}.
     *
     * <p>Not filtered by actor for non-super_admins the way audit rows are:
     * there is no actor. Nobody in this system did it.
     */
    private Stream<FeedItem> hrmsItemsFor(CurrentUser caller, int limit) {
        if (!caller.role().isAtLeast(AppRole.ADMIN)) {
            return Stream.empty();
        }
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
                notification.summary(),
                notification.receivedAt());
    }

    private static FeedItem toItem(AuditEntry entry) {
        return new FeedItem(
                Long.toString(entry.id()), entry.actorEmail(), summaryFor(entry), entry.occurredAt());
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
            case AuditAction.INTEGRATION_CONNECT -> "Connected an integration";
            case AuditAction.INTEGRATION_UPDATE -> "Updated an integration";
            case AuditAction.INTEGRATION_ROTATE_SECRET -> "Rotated an integration's signing secret";
            case AuditAction.INTEGRATION_DISCONNECT -> "Disconnected an integration";
            case AuditAction.CLAUDE_USAGE_MEMBER_INVITE -> "Invited someone to Claude Usage";
            case AuditAction.CLAUDE_USAGE_MEMBER_ROLE_CHANGE -> "Changed a Claude Usage member's role";
            case AuditAction.CLAUDE_USAGE_MEMBER_BUDGET_CHANGE -> "Changed a Claude Usage member's budget";
            case AuditAction.CLAUDE_USAGE_MEMBER_REMOVE -> "Removed a Claude Usage member";
            default -> entry.action();
        };
    }
}
