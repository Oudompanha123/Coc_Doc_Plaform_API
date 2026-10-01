package com.coc.docs.notify;

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
     * Somebody added this account to an AX Renewal project (ADR-074). Goes to
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
            "project");

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
