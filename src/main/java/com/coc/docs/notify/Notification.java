package com.coc.docs.notify;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

/**
 * One notification, addressed to one person.
 *
 * <p>The wording is not here. A row carries the {@link NotificationType} and
 * the facts that type needs; the sentence is rendered from those when it is
 * read (ADR-034 decision 2), so improving a message applies to everything
 * already sent rather than only to what comes next.
 *
 * @param type {@link NotificationType#key()} rather than the enum, because a
 *     row written before a type was removed must still load. An unknown key is
 *     a notification this build cannot render, which is a thing to skip rather
 *     than a thing to crash on.
 * @param targetId what it is about, for the deep link. Null for a notification
 *     about nothing in particular.
 * @param payload the facts, and no more of them than the type needs — this row
 *     is readable by its recipient (NFR-SEC-PII).
 * @param readAt null until the recipient has seen it.
 *
 * <p>// req: FR-AUTH-008, ADR-034
 */
public record Notification(
        UUID id,
        UUID accountId,
        String type,
        String targetId,
        JsonNode payload,
        Instant createdAt,
        Instant readAt) {

    public boolean isRead() {
        return readAt != null;
    }
}
