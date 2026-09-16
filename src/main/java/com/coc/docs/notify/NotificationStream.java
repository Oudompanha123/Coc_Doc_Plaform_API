package com.coc.docs.notify;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * An open connection per browser tab, so the bell changes without being asked.
 *
 * <p><b>Why this exists.</b> The bell polled once a minute and push arrived
 * through Firebase. Neither is the answer for somebody watching the screen:
 * a minute is long enough to look like nothing happened, and Firebase is
 * configured on almost no developer machine and on no deployment that has not
 * chosen a project yet (ADR-025) — so for most people the immediate path did
 * not exist at all. Server-sent events need no third party, no credentials and
 * no service worker.
 *
 * <p><b>Why SSE and not WebSocket.</b> Everything here travels one way, from
 * the server to a tab that only ever reacts. A WebSocket would add a protocol
 * upgrade, a second security surface and its own reconnection logic to carry
 * the same one-way message. SSE is an HTTP response that stays open: the
 * existing session cookie authenticates it, the existing CORS rule covers it,
 * and the browser reconnects on its own when it drops.
 *
 * <p><b>What is sent is a nudge, never the notification.</b> The payload is a
 * type key, and the client re-reads the queue and the session list it already
 * trusts. Sending the content would make this a second source of truth that
 * can disagree with those two — and a stream that a tab can miss entirely
 * while it is asleep is the worst possible place for the only copy of
 * something.
 *
 * <p>// req: FR-AUTH-007, ADR-025, ADR-027
 */
@Service
public class NotificationStream {

    private static final Logger log = LoggerFactory.getLogger(NotificationStream.class);

    /**
     * How long a connection is held before the server closes it and the browser
     * opens another.
     *
     * <p>Not indefinite. An emitter that never times out becomes a leak the day
     * something upstream drops connections without telling either end, and a
     * reconnect every half hour costs one request. Deliberately longer than any
     * proxy idle timeout is likely to be, with the heartbeat below making sure
     * the connection is never actually idle.
     */
    private static final long TIMEOUT_MS = 30 * 60 * 1000L;

    /**
     * Connections per account, not per session.
     *
     * <p>Somebody signed in on a laptop and a phone has two, and both should
     * update. A queue rather than a single emitter because two tabs on one
     * machine are also two connections, and replacing one with the other would
     * leave a tab silently dead with no way to notice.
     */
    private final Map<UUID, Queue<SseEmitter>> connections = new ConcurrentHashMap<>();

    /** Opens a connection for this account and returns it for the controller to hand back. */
    public SseEmitter open(UUID accountId) {
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        connections.computeIfAbsent(accountId, id -> new ConcurrentLinkedQueue<>()).add(emitter);

        // All three, because a connection can end in three ways and a registry
        // that only cleans up after one of them grows for ever.
        emitter.onCompletion(() -> remove(accountId, emitter));
        emitter.onTimeout(() -> remove(accountId, emitter));
        emitter.onError(e -> remove(accountId, emitter));

        try {
            // Sent immediately so the browser sees a response rather than an
            // open socket with nothing on it. Some proxies will not treat a
            // response as started until the first byte arrives, and a client
            // that waits for one would sit at "connecting" indefinitely.
            emitter.send(SseEmitter.event().name("ready").data("ok"));
        } catch (IOException e) {
            remove(accountId, emitter);
        }

        log.debug("notification stream opened for account {}", accountId);
        return emitter;
    }

    /**
     * Tells every connection this account has that something changed.
     *
     * <p>Failures are not propagated. This is called from the same listener
     * that sends the push, after the transaction that caused it has committed;
     * a tab that has gone away must not turn into an error on a path that has
     * already succeeded.
     */
    public void publish(UUID accountId, NotificationType type) {
        Queue<SseEmitter> open = connections.get(accountId);
        if (open == null || open.isEmpty()) {
            return;
        }
        for (SseEmitter emitter : List.copyOf(open)) {
            try {
                emitter.send(SseEmitter.event().name("notification").data(type.key()));
            } catch (IOException | IllegalStateException e) {
                // The tab closed between the check and the send, or the
                // response was already committed. Both are ordinary.
                remove(accountId, emitter);
            }
        }
    }

    /**
     * Keeps connections from being closed for looking idle.
     *
     * <p>A proxy, a load balancer or a laptop going to sleep will drop an HTTP
     * connection that has carried nothing for a minute or two, and neither end
     * is told. A comment every twenty seconds is invisible to the client — SSE
     * ignores it — and is enough to keep the connection alive and to discover
     * promptly that it is not.
     *
     * <p>This is also the only thing that reaps connections for tabs that were
     * closed without the server noticing: the send fails, and the emitter is
     * removed.
     */
    @Scheduled(fixedRate = 20_000)
    void heartbeat() {
        connections.forEach((accountId, open) -> {
            for (SseEmitter emitter : List.copyOf(open)) {
                try {
                    emitter.send(SseEmitter.event().comment("keep-alive"));
                } catch (IOException | IllegalStateException e) {
                    remove(accountId, emitter);
                }
            }
        });
    }

    private void remove(UUID accountId, SseEmitter emitter) {
        Queue<SseEmitter> open = connections.get(accountId);
        if (open == null) {
            return;
        }
        open.remove(emitter);

        // Dropping the queue too, or the map keeps one empty queue per account
        // that has ever connected — on a long-running process, eventually the
        // whole account table.
        //
        // computeIfPresent rather than a check followed by a remove: another
        // tab can open between the two, and removing the queue it was just
        // added to would leave that connection registered nowhere and silent.
        connections.computeIfPresent(accountId, (id, queue) -> queue.isEmpty() ? null : queue);
    }

    /**
     * Ends every open connection.
     *
     * <p>Called on shutdown so that each browser sees its stream close and
     * reconnects, rather than holding a socket to a process that has gone and
     * waiting out the thirty-minute timeout to find out. A rolling restart is
     * the ordinary case for this.
     */
    @PreDestroy
    public void completeAll() {
        connections.values().forEach(open -> {
            for (SseEmitter emitter : List.copyOf(open)) {
                try {
                    emitter.complete();
                } catch (RuntimeException e) {
                    // Already gone. Nothing here is worth failing a shutdown.
                }
            }
        });
        connections.clear();
    }

    /** Open connections, for tests and for a health check that may want it later. */
    public int openConnections() {
        return connections.values().stream().mapToInt(Queue::size).sum();
    }
}
