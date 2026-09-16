package com.coc.docs.web;

import com.coc.docs.auth.CurrentUser;
import com.coc.docs.notify.NotificationStream;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * A connection the browser holds open, so the bell changes as things happen.
 *
 * <p>The bell polled once a minute, and the immediate path was a Firebase push
 * that most machines are not configured for (ADR-025). This is the one that
 * always works: an HTTP response that stays open, authenticated by the session
 * cookie every other route already uses.
 *
 * <p><b>The account comes from the resolved session and there is no parameter
 * here.</b> Same rule as {@code /auth/sessions} and {@code DeviceTokenController}:
 * a caller can only ever open a stream for themselves, because there is nothing
 * to point at anybody else (NFR-SEC-PII).
 *
 * <p>Events carry a type key and nothing more — see {@link NotificationStream}
 * for why the content is deliberately not sent.
 *
 * <p>// req: FR-AUTH-007, NFR-SEC-PII, ADR-025, ADR-027
 */
@RestController
@RequestMapping("/api/v1/notifications")
@Tag(name = "Notifications", description = "Push notification destinations")
public class NotificationStreamController {

    private final NotificationStream stream;

    public NotificationStreamController(NotificationStream stream) {
        this.stream = stream;
    }

    @Operation(
            summary = "Subscribe to this account's notifications",
            description =
                    "A server-sent event stream. Each event names a notification type; the client"
                            + " re-reads the queue or session list it already trusts. The connection"
                            + " is closed after 30 minutes and the browser reopens it by itself.")
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter stream(@AuthenticationPrincipal CurrentUser caller) {
        return stream.open(caller.account().id());
    }
}
