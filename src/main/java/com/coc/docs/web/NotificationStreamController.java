package com.coc.docs.web;

import com.coc.docs.auth.CurrentUser;
import com.coc.docs.notify.NotificationStream;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
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

    private static final Logger log = LoggerFactory.getLogger(NotificationStreamController.class);

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

    /**
     * A browser that went away mid-stream, which is how every stream ends.
     *
     * <p>Closing a tab, navigating, sleeping a laptop: the next write to that
     * connection fails, and on Windows it reads like a catastrophe —
     *
     * <pre>
     * ERROR ... unhandled exception
     * java.io.IOException: An established connection was aborted by the
     * software in your host machine
     *     at ... SseEmitter.send
     *     at ... NotificationStream.heartbeat
     * </pre>
     *
     * <p>— forty frames of it, from {@code ApiExceptionHandler}, whose whole
     * premise is that anything reaching it is a bug. {@link NotificationStream}
     * already catches the failure and drops the connection; what arrives here
     * is Spring's error dispatch for an async response that can no longer be
     * written, raised after the fact.
     *
     * <p>Handled on this controller rather than in the global advice so the
     * quietening is exactly this endpoint's. An {@code IOException} from
     * anywhere else still reaches {@code ApiExceptionHandler} and is still an
     * error, because everywhere else it is one.
     *
     * <p>No body: the connection this would be written to is the one that just
     * went away.
     *
     * <p>// req: ADR-028
     */
    @ExceptionHandler(IOException.class)
    @ResponseStatus(HttpStatus.OK)
    void clientWentAway(IOException e) {
        log.debug("notification stream closed by the client: {}", e.getMessage());
    }
}
