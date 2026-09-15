package com.coc.docs.notify;

/**
 * What a notification is about, for the client to act on.
 *
 * <p>Deliberately not the subject's name or address — see
 * {@link NotificationType} for why a push payload carries no identity. An id
 * and a picture URL disclose nothing on a lock screen, and both are what the
 * banner needs to be useful: the id so a click opens the right thing rather
 * than a list to search, the picture so the reader recognises who it is about
 * before reading a word.
 *
 * @param id the subject, appended to the deep link. Null for a notification
 *     that is about nothing in particular.
 * @param iconUrl a picture for the banner, or null. Null for a password
 *     registration, which has no provider picture; the client falls back to
 *     initials rather than a stand-in face.
 * @see NotificationType
 */
public record NotificationTarget(String id, String iconUrl) {

    /** For a notification with no particular subject. */
    public static NotificationTarget none() {
        return new NotificationTarget(null, null);
    }
}
