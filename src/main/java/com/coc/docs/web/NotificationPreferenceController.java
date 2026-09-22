package com.coc.docs.web;

import com.coc.docs.auth.CurrentUser;
import com.coc.docs.notify.NotificationPreferenceService;
import com.coc.docs.notify.NotificationType;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The caller's own notification preferences — the Settings page's
 * "Notifications" toggles (ADR-041).
 *
 * <p>The account comes from the resolved session and no route here takes one,
 * so a caller can only ever read or change their own (NFR-SEC-PII) — same
 * shape as {@link NotificationInboxController}.
 *
 * <p>// req: ADR-041
 */
@RestController
@RequestMapping("/api/v1/notifications/preferences")
@Tag(name = "Notifications", description = "Push notification destinations")
public class NotificationPreferenceController {

    private final NotificationPreferenceService preferences;

    public NotificationPreferenceController(NotificationPreferenceService preferences) {
        this.preferences = preferences;
    }

    /** Every real notification type, with whether this caller currently hears about it. */
    @Operation(summary = "The caller's own notification preferences")
    @GetMapping
    ApiDtos.NotificationPreferenceListResponse list(@AuthenticationPrincipal CurrentUser caller) {
        return new ApiDtos.NotificationPreferenceListResponse(
                preferences.listFor(caller).stream().map(ApiDtos.NotificationPreferenceResponse::of).toList());
    }

    /**
     * Turns one notification type on or off for the caller.
     *
     * @param type {@link NotificationType#key()} — the same identifier the
     *     list above returns, not the Java enum name.
     */
    @Operation(summary = "Turn one notification type on or off for the caller")
    @PutMapping("/{type}")
    ResponseEntity<Void> update(
            @PathVariable String type,
            @RequestBody ApiDtos.UpdateNotificationPreferenceRequest body,
            @AuthenticationPrincipal CurrentUser caller) {
        NotificationType resolved =
                NotificationType.fromKey(type).orElseThrow(() -> ApiException.notFound("notification type " + type));
        preferences.setEnabled(caller, resolved, body.enabled());
        return ResponseEntity.noContent().build();
    }
}
