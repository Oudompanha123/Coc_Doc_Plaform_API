package com.coc.docs.notify;

import com.coc.docs.account.AccountRegisteredEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Tells the people who can approve that somebody is waiting.
 *
 * <p>Two annotations carry the whole design, and each answers a failure this
 * project has already had:
 *
 * <p><b>{@code AFTER_COMMIT}</b> — on 2026-09-14 a transactional sign-in
 * created an application row and then reported "awaiting approval" by
 * throwing, which rolled the row back: the applicant was told to wait for a
 * record that no longer existed, and the queue stayed empty however many
 * people applied. A notification sent inline would have announced those ghosts
 * to every super admin. An after-commit listener cannot fire for a transaction
 * that did not commit.
 *
 * <p><b>{@code @Async}</b> — after-commit still runs on the request thread, so
 * a slow or unreachable FCM would hold up the response to somebody who has
 * just registered. Registration does not wait on a notification.
 *
 * <p>Nothing is rethrown. The applicant is already saved and the pending queue
 * is the authority on who is waiting; a failure here costs a convenience, not
 * a registration. It is logged rather than swallowed, because a silently
 * undelivered notification looks exactly like nobody having registered a
 * device.
 *
 * <p>// req: ADR-017, ADR-025
 */
@Component
public class RegistrationNotifier {

    private static final Logger log = LoggerFactory.getLogger(RegistrationNotifier.class);

    private final NotificationService notifications;

    public RegistrationNotifier(NotificationService notifications) {
        this.notifications = notifications;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onAccountRegistered(AccountRegisteredEvent event) {
        try {
            notifications.notifyApprovers(NotificationType.NEW_REGISTRATION);
        } catch (RuntimeException e) {
            log.error("could not notify approvers about account {}", event.accountId(), e);
        }
    }
}
