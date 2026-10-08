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
        return send(tokens, type, target, type.body());
    }

    /**
     * As {@link #send(Collection, NotificationType, NotificationTarget)}, with
     * the banner text supplied by the caller.
     *
     * <p>A parameter rather than a field on the type, because some
     * notifications only have something worth saying at the moment they are
     * sent — a new-device sign-in says which device, and a constant cannot.
     * The caller is responsible for keeping that text free of identity
     * (ADR-025 decision 6); it renders on a lock screen.
     */
    public List<String> send(
            Collection<String> tokens, NotificationType type, NotificationTarget target, String body) {
        return sendBanner(tokens, type, target, new PushBanner(type.title(), body, null, null));
    }

    /**
     * As {@link #send(Collection, NotificationType, NotificationTarget, String)},
     * with the banner's title and large image chosen by the caller (ADR-103) —
     * an announcement's own title and text, with the site logo.
     *
     * <p>A separate name rather than another four-argument {@code send}: the
     * two would be ambiguous to every caller and mock that passes a bare
     * {@code null}.
     */
    public List<String> sendBanner(
            Collection<String> tokens, NotificationType type, NotificationTarget target, PushBanner banner) {
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
            dead.addAll(sendBatch(fcm, batch, type, target, banner));
        }
        return dead;
    }

    private List<String> sendBatch(
            FirebaseMessaging fcm,
            List<String> batch,
            NotificationType type,
            NotificationTarget target,
            PushBanner banner) {
        MulticastMessage.Builder builder = MulticastMessage.builder()
                .addAllTokens(batch)
                .setNotification(Notification.builder()
                        .setTitle(banner.title())
                        .setBody(banner.body())
                        .build())
                // Read by the service worker to route the click. Sent as data
                // rather than baked into the notification so the client decides
                // what to do with it — a phone and a browser want different
                // things from the same event.
                .putData("type", type.key())
                // The banner's collapse key. The type, unless the banner asks
                // for its own: a browser replaces a notification that shares a
                // tag *without showing it*, so one tag for every announcement
                // meant only the first ever popped up (ADR-103).
                .putData("tag", banner.tag() == null ? type.key() : banner.tag())
                // The exact thing to act on, so a click opens the approval
                // dialog rather than dropping the reader on a list to search.
                // An id, not a name — the banner text stays free of identity
                // (ADR-025 decision 6) and a UUID discloses nothing on a lock
                // screen.
                .putData("link", type.deepLink(frontendOrigin, target.id()))
                // The subject picture, shown in the banner. Absent for a
                // password registration; the client draws initials rather than
                // falling back to an app logo, which says nothing about who
                // this is.
                .putData("icon", target.iconUrl() == null ? "" : target.iconUrl());
        if (banner.image() != null) {
            // A large picture under the text (ADR-103) — the site logo on an
            // announcement. A path on the frontend's own origin, which is
            // where the service worker that draws the banner runs.
            builder.putData("image", banner.image());
        }
        MulticastMessage message = builder.build();

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
