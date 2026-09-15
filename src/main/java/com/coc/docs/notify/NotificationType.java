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
            "/users-roles?tab=pending");

    private final String key;
    private final String title;
    private final String body;
    private final String path;

    NotificationType(String key, String title, String body, String path) {
        this.key = key;
        this.title = title;
        this.body = body;
        this.path = path;
    }

    /** Stable identifier for logs and for the client, which may route on it. */
    public String key() {
        return key;
    }

    public String title() {
        return title;
    }

    public String body() {
        return body;
    }

    /** Path within the frontend, resolved against its origin by the client. */
    public String path() {
        return path;
    }
}
