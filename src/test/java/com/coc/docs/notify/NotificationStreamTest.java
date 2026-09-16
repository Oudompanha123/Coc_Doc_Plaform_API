package com.coc.docs.notify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * The registry of open connections.
 *
 * <p>What is worth pinning is the bookkeeping, because getting it wrong is
 * invisible: a connection left in the map after its tab closed is a leak that
 * grows for as long as the process runs, and one removed too eagerly is a tab
 * that goes quiet with nothing to show it has.
 *
 * <p>// req: ADR-025, ADR-027
 */
class NotificationStreamTest {

    private final NotificationStream stream = new NotificationStream();
    private final UUID dana = UUID.randomUUID();
    private final UUID sam = UUID.randomUUID();

    @Test
    @DisplayName("two tabs on one account are two connections, not one")
    void tabsDoNotReplaceEachOther() {
        stream.open(dana);
        stream.open(dana);

        // Keeping only the newest would leave the other tab registered nowhere,
        // silent, and with no way for it or anyone else to notice.
        assertThat(stream.openConnections()).isEqualTo(2);
    }

    @Test
    @DisplayName("the heartbeat reaps a connection whose tab has gone")
    void heartbeatDeregistersDeadConnections() {
        SseEmitter emitter = stream.open(dana);
        assertThat(stream.openConnections()).isEqualTo(1);

        // A closed tab, from the registry's point of view: the next write to it
        // fails. In a servlet container the completion callback usually gets
        // there first; this is the path for when nothing tells us, which is the
        // whole reason the heartbeat writes rather than merely counting.
        emitter.complete();
        stream.heartbeat();

        assertThat(stream.openConnections()).isZero();
    }

    @Test
    @DisplayName("one dead tab does not take the live one with it")
    void reapingOneLeavesTheOther() {
        SseEmitter first = stream.open(dana);
        stream.open(dana);

        first.complete();
        stream.heartbeat();

        assertThat(stream.openConnections()).isEqualTo(1);
    }

    @Test
    @DisplayName("publishing to a dead connection removes it rather than throwing")
    void publishReapsToo() {
        SseEmitter emitter = stream.open(dana);
        emitter.complete();

        assertThatCode(() -> stream.publish(dana, NotificationType.NEW_DEVICE_SIGN_IN))
                .doesNotThrowAnyException();

        // This is called from an after-commit listener, on a path that has
        // already succeeded. A tab that went away must never turn into an error
        // there.
        assertThat(stream.openConnections()).isZero();
    }

    @Test
    @DisplayName("publishing to an account with nothing open is not an error")
    void publishingToNobodyIsFine() {
        // The ordinary case. Almost nobody has the app open at the moment
        // somebody registers, and a notification path that throws when nobody
        // is listening would turn that into a failed sign-in.
        assertThatCode(() -> stream.publish(sam, NotificationType.NEW_REGISTRATION))
                .doesNotThrowAnyException();
        assertThat(stream.openConnections()).isZero();
    }

    @Test
    @DisplayName("one account's connection is not another account's")
    void connectionsAreNotShared() {
        stream.open(dana);

        stream.publish(sam, NotificationType.NEW_DEVICE_SIGN_IN);

        // Nothing observable happens, which is the assertion: the registry is
        // keyed by account, and a sign-in alert reaching somebody else is the
        // one failure this must not have.
        assertThat(stream.openConnections()).isEqualTo(1);
    }

    @Test
    @DisplayName("the heartbeat leaves live connections alone")
    void heartbeatKeepsLiveConnections() {
        stream.open(dana);
        stream.open(sam);

        stream.heartbeat();

        assertThat(stream.openConnections()).isEqualTo(2);
    }
}
