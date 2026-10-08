package com.coc.docs.notify;

import com.coc.docs.account.AppRole;

/**
 * What a notification says and where it leads.
 *
 * <p>The type is data rather than a class per notification. Adding "a page was
 * published" is a constant here plus something that publishes the event —
 * delivery, recipient resolution and token pruning are untouched. A sender per
 * notification is the shape that makes the fifth one a rewrite.
 *
 * <p><b>Nothing here identifies anybody.</b> A push renders on a lock screen,
 * on a device that may be on a train, delivered through Google's
 * infrastructure to a client this application does not control. The recipient
 * is one tap from the queue, where the detail is shown to somebody who has
 * authenticated. See ADR-025 decision 6.
 *
 * <p>// req: ADR-025
 */
public enum NotificationType {

    /**
     * A registration reached the approval queue (ADR-017). Fired once the
     * account row exists — which for the password path is after the emailed
     * code is confirmed (ADR-021), so an abandoned or mistyped registration
     * never reaches anyone's lock screen.
     */
    NEW_REGISTRATION(
            "registration.pending",
            "New registration",
            "1 new registration awaiting approval",
            "/users-roles?tab=pending",
            "approve"),

    /**
     * Somebody signed in to an account from a device it has not been used on
     * before (ADR-027). Goes to the account's owner and to nobody else: this
     * is the one notification in the system whose recipient is its subject.
     *
     * <p>The default body is replaced at send time with the device — "Chrome
     * on Windows" — because a fixed string cannot say the one thing that makes
     * this alert actionable. The device is not identity: it names a browser
     * and an operating system, which is what lets the reader answer "was that
     * me" without unlocking anything. Where from and exactly when stay inside
     * the app, behind the session.
     */
    NEW_DEVICE_SIGN_IN(
            "auth.new-device",
            "New sign-in",
            "Your account was signed in to from a new device",
            "/settings",
            "session"),

    /**
     * Somebody added this account to an AX Renewal project (ADR-075). Goes to
     * the account added and to nobody else, and only when they were actually
     * added — not on a repeated add of an existing member, and not when an
     * account adds itself.
     *
     * <p>The body is this fixed sentence and never the project's name: a
     * renewal project's name says which legacy system a team is replacing,
     * which is not something to put on a lock screen. The project is one tap
     * away, behind a session — the deep link opens it.
     */
    AX_RENEWAL_MEMBER_ADDED(
            "ax-renewal.member-added",
            "Added to a project",
            "You were added to an AX Renewal project",
            "/ax-renewal",
            "project"),

    /**
     * An implementation run in a project this account belongs to has reached
     * {@code awaiting_review} (ADR-079): its changes are generated and wait
     * for somebody to approve or reject them. Goes to the project's members —
     * the starter included when the result took minutes to generate, since
     * they are the one waiting; not the starter of a replay, who is looking at
     * it already.
     *
     * <p>A fixed sentence for the same reason as the member-added one: the
     * project's name says which legacy system is being replaced.
     */
    AX_RENEWAL_RUN_READY(
            "ax-renewal.run-ready",
            "Ready for review",
            "An AX Renewal implementation run is ready for review",
            "/ax-renewal",
            "project"),

    /**
     * Somebody commented on an AX Renewal project this account belongs to
     * (ADR-080). Goes to every member but the author, once the comment is
     * saved.
     *
     * <p>A fixed sentence: neither the project's name nor the comment's text
     * goes on a lock screen. Opening it opens the project, whose one comment
     * thread is always on screen there.
     */
    AX_RENEWAL_COMMENT_ADDED(
            "ax-renewal.comment-added",
            "New comment",
            "Someone commented on an AX Renewal project",
            "/ax-renewal",
            "project"),

    /**
     * Somebody else approved or rejected the implementation run this account
     * started (ADR-081). Goes to the starter only, and only while they are
     * still a member; nobody is told about their own decision.
     *
     * <p>The body is replaced at send time with the outcome — {@link
     * #RUN_APPROVED_BODY} or {@link #RUN_REJECTED_BODY} — because "reviewed"
     * alone does not say whether there is anything left to do. Both are fixed
     * sentences: no project name and no reviewer, since this renders on a
     * lock screen. Who reviewed it is in the project's run history.
     */
    AX_RENEWAL_RUN_REVIEWED(
            "ax-renewal.run-reviewed",
            "Run reviewed",
            "Your AX Renewal implementation run was reviewed",
            "/ax-renewal",
            "project"),

    /**
     * An admin published an announcement (ADR-098). Goes to every active
     * account except the one who published it.
     *
     * <p>The push body is this fixed sentence and never the announcement's
     * title: an announcement is internal company news, and a lock screen is
     * not inside the company. The bell row carries the title in its payload,
     * which only its signed-in recipient reads; opening it fetches the
     * current text, so an edit after publishing is what the reader sees.
     */
    ANNOUNCEMENT_PUBLISHED(
            "announcement.published",
            "Announcements",
            "There is a new announcement",
            "/home",
            "announcement");

    /** {@link #AX_RENEWAL_RUN_REVIEWED}'s body for a run that was approved — and so applied (ADR-081). */
    public static final String RUN_APPROVED_BODY = "Your AX Renewal implementation run was approved and applied";

    /** {@link #AX_RENEWAL_RUN_REVIEWED}'s body for a run that was rejected (ADR-081). */
    public static final String RUN_REJECTED_BODY = "Your AX Renewal implementation run was rejected";

    private final String key;
    private final String title;
    private final String body;
    private final String path;
    private final String targetParam;

    NotificationType(String key, String title, String body, String path, String targetParam) {
        this.key = key;
        this.title = title;
        this.body = body;
        this.path = path;
        this.targetParam = targetParam;
    }

    /** Stable identifier for logs and for the client, which may route on it. */
    public String key() {
        return key;
    }

    /**
     * The member whose {@link #key()} matches, for a client addressing a type
     * by its wire identifier (e.g. a preference toggle) rather than by enum
     * name. Empty for an unknown key — a type this build has retired or one
     * the client made up — which the caller turns into a 404 rather than a
     * 500, the same way {@link #key()}'s own doc explains an unrecognised row
     * is a thing to skip, not a thing to crash on.
     */
    public static java.util.Optional<NotificationType> fromKey(String key) {
        return java.util.Arrays.stream(values()).filter(t -> t.key.equals(key)).findFirst();
    }

    /**
     * The lowest role that can ever receive this type, so a preference switch
     * is only offered to somebody it can reach (a {@code user} has no AX
     * Renewal access and never approves a registration, so those switches
     * would do nothing for them). Mirrors who each sender actually picks:
     * {@code NotificationService.notifyApprovers} for a registration, AX
     * Renewal's {@code DEVELOPER} floor in {@code SecurityConfig} for its four
     * types, and every active account for the other two.
     *
     * <p>A switch rather than a constructor argument so that a new type does
     * not compile until somebody has decided who it is for.
     *
     * <p>// req: ADR-041
     */
    public AppRole minimumRole() {
        return switch (this) {
            case NEW_REGISTRATION -> AppRole.SUPER_ADMIN;
            case AX_RENEWAL_MEMBER_ADDED, AX_RENEWAL_RUN_READY, AX_RENEWAL_COMMENT_ADDED, AX_RENEWAL_RUN_REVIEWED ->
                AppRole.DEVELOPER;
            case NEW_DEVICE_SIGN_IN, ANNOUNCEMENT_PUBLISHED -> AppRole.USER;
        };
    }

    /** True when somebody whose highest role is {@code role} can receive this type. */
    public boolean isFor(AppRole role) {
        return role.isAtLeast(minimumRole());
    }

    public String title() {
        return title;
    }

    /** Used when the sender has nothing more specific to say. */
    public String body() {
        return body;
    }

    /** Path within the frontend, resolved against its origin by the client. */
    public String path() {
        return path;
    }

    /**
     * Where a click should land, with the subject named in the query string.
     *
     * <p>Built here rather than by the sender because the separator depends on
     * the path, and the sender had it wrong for any path without a query of
     * its own: it appended {@code &} unconditionally, which happened to work
     * for the one type that existed and would have produced
     * {@code /settings&session=…} for this one.
     */
    public String deepLink(String origin, String targetId) {
        String url = origin + path;
        if (targetId == null) {
            return url;
        }
        return url + (path.contains("?") ? "&" : "?") + targetParam + "=" + targetId;
    }
}
