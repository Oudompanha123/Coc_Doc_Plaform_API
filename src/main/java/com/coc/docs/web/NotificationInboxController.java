package com.coc.docs.web;

import com.coc.docs.auth.CurrentUser;
import com.coc.docs.notify.Notification;
import com.coc.docs.notify.NotificationInboxService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The caller's own notifications — what the bell shows, and what it has seen.
 *
 * <p><b>Distinct from {@code GET /api/v1/notifications}</b>, which is the
 * activity feed derived from {@code audit_log} (ADR-033). That answers "what
 * has been happening"; this answers "what was addressed to me, and have I read
 * it". They stay separate deliberately (ADR-034 decision 6): a feed of
 * everything that happened is genuinely better derived, and an event addressed
 * to one person is genuinely better stored.
 *
 * <p>The account comes from the resolved session and no route here takes one,
 * so a caller can only ever read or mark their own (NFR-SEC-PII).
 *
 * <p>// req: FR-AUTH-008, NFR-SEC-PII, ADR-034
 */
@RestController
@RequestMapping("/api/v1/notifications/inbox")
@Tag(name = "Notifications", description = "Push notification destinations")
public class NotificationInboxController {

    private final NotificationInboxService inbox;

    public NotificationInboxController(NotificationInboxService inbox) {
        this.inbox = inbox;
    }

    /**
     * This account's notifications, newest first, with the unread count.
     *
     * <p>The count comes back with the list rather than from a second endpoint:
     * the badge and the list are read together on every poll, and two round
     * trips can disagree with each other.
     *
     * @param limit clamped rather than refused — this drives a dropdown, and a
     *     decoration should not answer 400 because somebody asked for too many.
     */
    @Operation(summary = "The caller's own notifications, newest first")
    @GetMapping
    ApiDtos.NotificationInboxResponse list(
            @RequestParam(required = false, defaultValue = "30") int limit,
            @AuthenticationPrincipal CurrentUser caller) {

        List<Notification> items = inbox.listFor(caller, limit);
        return new ApiDtos.NotificationInboxResponse(
                items.stream().map(ApiDtos.NotificationResponse::of).toList(),
                inbox.unreadCountFor(caller));
    }

    /**
     * One page of this account's notifications, newest first — what the bell
     * loads as it is scrolled once "View all notifications" expands it (ADR-106).
     *
     * <p>Separate from {@link #list} rather than a {@code before} parameter on
     * it: that one answers "what should the bell show" (the newest few plus
     * every unread), this one walks the whole history in order. Keyset paging:
     * pass the last row's {@code createdAt} and {@code id} to get the next page.
     *
     * @param limit clamped to 1–100, like {@link #list}
     * @param before with {@code beforeId}, the last row already held; omit
     *     both for the first page
     * @param unread only notifications not yet seen
     */
    @Operation(summary = "One page of the caller's own notifications, newest first")
    @GetMapping("/history")
    ApiDtos.NotificationPageResponse history(
            @RequestParam(required = false, defaultValue = "20") int limit,
            @RequestParam(required = false)
                    @org.springframework.format.annotation.DateTimeFormat(
                            iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME)
                    Instant before,
            @RequestParam(required = false) UUID beforeId,
            @RequestParam(required = false, defaultValue = "false") boolean unread,
            @AuthenticationPrincipal CurrentUser caller) {

        NotificationInboxService.Page page = inbox.pageFor(caller, before, beforeId, unread, limit);
        return new ApiDtos.NotificationPageResponse(
                page.items().stream().map(ApiDtos.NotificationResponse::of).toList(),
                page.hasMore(),
                inbox.unreadCountFor(caller));
    }

    /**
     * Marks one notification read.
     *
     * <p>204 whether or not a row changed. An id that is unknown, already read,
     * or belongs to somebody else are indistinguishable to the caller, because
     * the difference is only useful to someone enumerating other people's
     * notifications.
     */
    @Operation(summary = "Mark one notification read")
    @PostMapping("/{id}/read")
    ResponseEntity<Void> markRead(
            @PathVariable UUID id, @AuthenticationPrincipal CurrentUser caller) {
        inbox.markRead(caller, id);
        return ResponseEntity.noContent().build();
    }

    /** Marks everything this account has not seen as read. */
    @Operation(summary = "Mark every notification read")
    @PostMapping("/read-all")
    ResponseEntity<Void> markAllRead(@AuthenticationPrincipal CurrentUser caller) {
        inbox.markAllRead(caller);
        return ResponseEntity.noContent().build();
    }
}
