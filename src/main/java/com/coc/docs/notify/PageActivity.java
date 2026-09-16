package com.coc.docs.notify;

import java.time.Instant;

/**
 * One page's activity within a reporting window, as read back from the audit
 * trail.
 *
 * <p>Deliberately raw: this record says what happened and what state the page
 * is in, and takes no position on how that reads to a human. Turning
 * {@code lastAction} plus {@code hasDraft} into the word "Draft pending" is
 * {@link ActivityDigestService}'s job, so that the wording can change without
 * a query change and the query can change without touching the wording.
 *
 * @param sectionSlug the doc set the page belongs to, e.g. {@code jex2.0}
 * @param pageSlug the page's slug
 * @param pageTitle the page's title — published {@code en} where one exists,
 *     otherwise the most recently updated version, otherwise the slug
 * @param lastAt when the most recent event in the window occurred
 * @param lastActor who caused it — display name where the account still
 *     exists, the audit trail's denormalised email otherwise
 * @param lastActorEmail that person's email, read from the audit row rather
 *     than from {@code account}, so it is still there after the account is
 *     deleted — the reason the column is denormalised in the first place
 * @param lastAction the most recent {@code AuditAction} in the window
 * @param eventCount how many events the page saw in the window
 * @param pageDeleted whether the page is currently soft-deleted
 * @param hasDraft whether an unpublished draft row currently exists
 * @param hasPublished whether a published row currently exists
 *
 * <p>// req: FR-OPS-001, NFR-OPS-AUDIT
 */
public record PageActivity(
        String sectionSlug,
        String pageSlug,
        String pageTitle,
        Instant lastAt,
        String lastActor,
        String lastActorEmail,
        String lastAction,
        int eventCount,
        boolean pageDeleted,
        boolean hasDraft,
        boolean hasPublished) {}
