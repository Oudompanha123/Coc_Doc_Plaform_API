package com.coc.docs.notify;

import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.MessagingErrorCode;
import com.google.firebase.messaging.MulticastMessage;
import com.google.firebase.messaging.Notification;
import com.google.firebase.messaging.SendResponse;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Hands a notification to Firebase Cloud Messaging.
 *
 * <p>{@code FirebaseMessaging} is taken through an {@link ObjectProvider} for
 * the same reason {@code AccountInviteMailer} takes its sender that way: six
 * people run this locally against their own schema, and a component that
 * refuses to start without a Google service account would make the whole
 * application undevelopable for anyone who had not been handed one. With no
 * credentials configured there is no bean, and this logs what it would have
 * sent (never the token — see below) and returns.
 *
 * <p><b>Dead tokens are returned, not thrown.</b> FCM answers per-message, so
 * one retired token among ten must not lose the other nine. The caller deletes
 * what comes back; a token FCM has retired otherwise stays in the table
 * forever and every later send wastes work on it.
 *
 * <p><b>Tokens are never logged.</b> A registration token is a capability: it
 * addresses a specific browser or phone, and anyone holding it and the server
 * credentials can push to that device. Counts are logged instead.
 *
 * <p>// req: ADR-025
 */
@Component
public class PushSender {

    private static final Logger log = LoggerFactory.getLogger(PushSender.class);

    /** FCM's cap for a single multicast. Larger sets are split. */
    private static final int BATCH_LIMIT = 500;

    private final ObjectProvider<FirebaseMessaging> messaging;
    private final String frontendOrigin;

    public PushSender(
            ObjectProvider<FirebaseMessaging> messaging,
            com.coc.docs.config.AppProperties properties) {
        this.messaging = messaging;
        this.frontendOrigin = properties.frontendOrigin();
    }

    /**
     * Sends to every token given.
     *
     * @return the tokens FCM reported as dead, for the caller to delete. Empty
     *     when everything was accepted, when there was nothing to send, or when
     *     Firebase is not configured — in none of those cases has a token been
     *     shown to be invalid, and deleting on a transport failure would
     *     unsubscribe people because the network blinked.
     */
    public List<String> send(Collection<String> tokens, NotificationType type, NotificationTarget target) {
        if (tokens.isEmpty()) {
            return List.of();
        }

        FirebaseMessaging fcm = messaging.getIfAvailable();
        if (fcm == null) {
            log.info(
                    "push not configured; would have sent {} to {} device(s)",
                    type.key(),
                    tokens.size());
            return List.of();
        }

        List<String> all = List.copyOf(tokens);
        List<String> dead = new ArrayList<>();
        for (int start = 0; start < all.size(); start += BATCH_LIMIT) {
            List<String> batch = all.subList(start, Math.min(start + BATCH_LIMIT, all.size()));
            dead.addAll(sendBatch(fcm, batch, type, target));
        }
        return dead;
    }

    private List<String> sendBatch(
            FirebaseMessaging fcm, List<String> batch, NotificationType type, NotificationTarget target) {
        MulticastMessage message = MulticastMessage.builder()
                .addAllTokens(batch)
                .setNotification(Notification.builder()
                        .setTitle(type.title())
                        .setBody(type.body())
                        .build())
                // Read by the service worker to route the click. Sent as data
                // rather than baked into the notification so the client decides
                // what to do with it — a phone and a browser want different
                // things from the same event.
                .putData("type", type.key())
                // The exact thing to act on, so a click opens the approval
                // dialog rather than dropping the reader on a list to search.
                // An id, not a name — the banner text stays free of identity
                // (ADR-025 decision 6) and a UUID discloses nothing on a lock
                // screen.
                .putData("link", frontendOrigin + type.path()
                        + (target.id() == null ? "" : "&approve=" + target.id()))
                // The subject picture, shown in the banner. Absent for a
                // password registration; the client draws initials rather than
                // falling back to an app logo, which says nothing about who
                // this is.
                .putData("icon", target.iconUrl() == null ? "" : target.iconUrl())
                .build();

        try {
            var response = fcm.sendEachForMulticast(message);
            List<String> dead = new ArrayList<>();
            List<SendResponse> responses = response.getResponses();
            for (int i = 0; i < responses.size(); i++) {
                SendResponse each = responses.get(i);
                if (each.isSuccessful()) {
                    continue;
                }
                MessagingErrorCode code = each.getException() == null
                        ? null
                        : each.getException().getMessagingErrorCode();
                // UNREGISTERED: the app was uninstalled or permission revoked.
                // INVALID_ARGUMENT: the token is malformed and never will work.
                // Anything else — quota, a transient upstream fault — is not
                // the token's fault and must not unsubscribe anyone.
                if (code == MessagingErrorCode.UNREGISTERED
                        || code == MessagingErrorCode.INVALID_ARGUMENT) {
                    dead.add(batch.get(i));
                }
            }
            log.info(
                    "push {}: {} delivered, {} failed, {} retired",
                    type.key(),
                    response.getSuccessCount(),
                    response.getFailureCount(),
                    dead.size());
            return dead;
        } catch (FirebaseMessagingException e) {
            // The whole batch failed — an expired service account, no network.
            // Nothing is known about any individual token, so nothing is
            // deleted. Logged loudly because a silently undelivered
            // notification is indistinguishable from nobody having registered.
            log.error("push {} failed for {} device(s)", type.key(), batch.size(), e);
            return List.of();
        }
    }
}
