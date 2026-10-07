package com.coc.docs.announcement;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * One admin-authored announcement (ADR-098).
 *
 * @param category one of {@link #CATEGORIES}; decides the reader's badge and
 *     nothing else — no category changes who receives it
 * @param status one of {@link #STATUS_DRAFT}, {@link #STATUS_PUBLISHED} or
 *     {@link #STATUS_ARCHIVED}. "Expired" is not a status: see {@link #isExpired}.
 * @param expiresAt null means it stays up until somebody unpublishes it
 * @param versionNo for optimistic locking; an update naming an older one is refused
 * @param publishedAt when it last went out; null for one never published
 * @param publishedByName display name of whoever last published it, or null
 *     when nobody has, or that account has since been deleted
 * @param updatedByName as {@code publishedByName}, for the last edit
 *
 * <p>// req: ADR-098
 */
public record Announcement(
        UUID id,
        String title,
        String body,
        String category,
        String status,
        Instant expiresAt,
        int versionNo,
        Instant publishedAt,
        String publishedByName,
        Instant createdAt,
        Instant updatedAt,
        String updatedByName) {

    public static final String STATUS_DRAFT = "draft";
    public static final String STATUS_PUBLISHED = "published";
    public static final String STATUS_ARCHIVED = "archived";

    /** Mirrors the {@code announcement.category} check constraint (V69). */
    public static final Set<String> CATEGORIES = Set.of("info", "update", "maintenance", "important");

    public boolean isPublished() {
        return STATUS_PUBLISHED.equals(status);
    }

    /**
     * Published, but past its expiry — hidden from readers while still
     * {@code published} in the table, so that lapsing needs no job to run.
     */
    public boolean isExpired(Instant now) {
        return isPublished() && expiresAt != null && !expiresAt.isAfter(now);
    }

    /** What a signed-in reader may see: published and not yet expired. */
    public boolean isLive(Instant now) {
        return isPublished() && !isExpired(now);
    }
}
