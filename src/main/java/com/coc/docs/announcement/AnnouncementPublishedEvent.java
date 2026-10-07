package com.coc.docs.announcement;

import java.util.UUID;

/**
 * An announcement was published (ADR-098).
 *
 * <p>Raised by {@link AnnouncementService#publish} once the status change and
 * its audit entry are written, and handled after the transaction commits
 * ({@code AnnouncementNotifier}). That ordering is the point: the bell rows,
 * the live-stream nudge and the push all go out only once the announcement is
 * really published and readable, so an open tab that re-reads its inbox on the
 * nudge finds the new row instead of racing the commit, and a publish that
 * rolls back tells nobody.
 *
 * <p>Ids only, the same as the AX Renewal events: the notifier reads the
 * current title itself, after the commit.
 *
 * <p>// req: ADR-098, ADR-034
 */
public record AnnouncementPublishedEvent(UUID announcementId, UUID publisherId) {}
