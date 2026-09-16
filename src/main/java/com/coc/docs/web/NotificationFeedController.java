package com.coc.docs.web;

import com.coc.docs.auth.CurrentUser;
import com.coc.docs.notify.NotificationFeedService;
import io.swagger.v3.oas.annotations.Operation;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The caller's activity feed — what the topbar's message icon shows.
 *
 * <p><b>Read-only, and deliberately not under {@code /admin}.</b> Every signed-in
 * role may call it, because every role gets their own activity; what differs is
 * how much. {@link NotificationFeedService} decides that from the role on the
 * session, so this class takes no scope parameter a caller could widen.
 *
 * <p>Not to be confused with {@link AdminNotificationController}, which is a
 * {@code POST} that <em>sends</em> the digest email and is {@code super_admin}
 * only. This one sends nothing and is safe for a component to fetch on mount —
 * which is exactly why the two are separate routes rather than one path with
 * two methods.
 *
 * <p>No {@code @PreAuthorize}: ADR-011 keeps one enforcement point, so the
 * floor for this route is the line in {@code SecurityConfig} and nowhere else.
 *
 * <p>// req: FR-OPS-001, FR-AUTH-008, NFR-SEC-PII, NFR-OPS-AUDIT
 */
@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationFeedController {

    private final NotificationFeedService feed;

    public NotificationFeedController(NotificationFeedService feed) {
        this.feed = feed;
    }

    /**
     * Recent activity, newest first.
     *
     * @param limit how many to return. Out-of-range values are clamped rather
     *     than refused — this drives a dropdown, and a decoration should not
     *     answer 400 because a caller asked for too many.
     */
    @Operation(
            summary = "Recent activity for the caller",
            description = "A super_admin sees everyone's activity; every other role sees only their own. "
                    + "Read-only: unlike POST /api/v1/admin/notifications/digest, this sends no email.")
    @GetMapping
    List<ApiDtos.NotificationItem> list(
            @RequestParam(required = false, defaultValue = "20") int limit,
            @AuthenticationPrincipal CurrentUser caller) {

        return feed.feedFor(caller, limit).stream().map(ApiDtos.NotificationItem::of).toList();
    }
}
