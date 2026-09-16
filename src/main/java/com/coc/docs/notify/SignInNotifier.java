package com.coc.docs.notify;

import com.coc.docs.auth.NewDeviceSignInEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Tells an account owner that somebody signed in from a device they have not
 * used before.
 *
 * <p>The same two annotations as {@code RegistrationNotifier}, for the same two
 * reasons, and here they matter more rather than less:
 *
 * <p><b>{@code AFTER_COMMIT}</b> — sign-in is transactional and can still fail
 * after the session row is written. A warning about a session that was rolled
 * back is worse than no warning: it is a security alert for an event that did
 * not happen, and the reader has no way to find out it did not.
 *
 * <p><b>{@code @Async}</b> — this runs on the request thread otherwise, so a
 * slow or unreachable Firebase would sit in the middle of somebody's sign-in.
 * Nobody waits to be let in while we warn them about being let in.
 *
 * <p>Nothing is rethrown. The session exists, the audit entry exists, and the
 * Settings list shows the device whether or not this succeeded; a failure here
 * costs the prompt, not the record. It is logged at error rather than swallowed
 * because an alert that silently never arrives is indistinguishable from one
 * that was never warranted — and this is the notification where that
 * distinction is worth a line in the log.
 *
 * <p>// req: FR-AUTH-007, NFR-SEC-SESSION, ADR-025, ADR-027
 */
@Component
public class SignInNotifier {

    private static final Logger log = LoggerFactory.getLogger(SignInNotifier.class);

    private final NotificationService notifications;

    public SignInNotifier(NotificationService notifications) {
        this.notifications = notifications;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onNewDeviceSignIn(NewDeviceSignInEvent event) {
        try {
            notifications.notifyAccount(
                    event.accountId(),
                    NotificationType.NEW_DEVICE_SIGN_IN,
                    // The session, so a tap opens the alert with the option to
                    // end it. No picture: the subject of this notification is a
                    // browser, and the account's own avatar would suggest the
                    // sign-in has been vouched for.
                    new NotificationTarget(event.sessionId().toString(), null),
                    // The device and nothing else. Where from and when are in
                    // the app, behind a session, because this line renders on a
                    // lock screen (ADR-025 decision 6).
                    event.device());
        } catch (RuntimeException e) {
            log.error(
                    "could not warn account {} about a sign-in from {}",
                    event.accountId(),
                    event.device(),
                    e);
        }
    }
}
