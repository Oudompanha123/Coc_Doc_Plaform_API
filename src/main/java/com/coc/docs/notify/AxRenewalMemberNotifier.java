package com.coc.docs.notify;

import com.coc.docs.axrenewal.AxRenewalMemberAddedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Tells somebody they were added to an AX Renewal project (ADR-075).
 *
 * <p>The same two annotations as {@code SignInNotifier}, for the same reasons:
 *
 * <p><b>{@code AFTER_COMMIT}</b> — adding a member is transactional. A
 * notification for an add that then rolled back would send somebody to a
 * project they cannot open.
 *
 * <p><b>{@code @Async}</b> — otherwise a slow or unreachable push service
 * would sit inside the request of whoever pressed "Add member".
 *
 * <p>Everything else — the recipient's opt-out (ADR-041), storing the row in
 * the bell before any delivery (ADR-034), the open-tab stream, push and
 * pruning dead device tokens — is {@link NotificationService#notifyAccount}'s,
 * exactly as it is for a new-device sign-in. Nothing is rethrown: the member
 * row and its audit entry exist whether or not this succeeds, so a failure
 * here costs the prompt, not the membership.
 *
 * <p>// req: ADR-075, ADR-025, ADR-034, ADR-041
 */
@Component
public class AxRenewalMemberNotifier {

    private static final Logger log = LoggerFactory.getLogger(AxRenewalMemberNotifier.class);

    private final NotificationService notifications;

    public AxRenewalMemberNotifier(NotificationService notifications) {
        this.notifications = notifications;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onMemberAdded(AxRenewalMemberAddedEvent event) {
        try {
            notifications.notifyAccount(
                    event.accountId(),
                    NotificationType.AX_RENEWAL_MEMBER_ADDED,
                    // The project, so a tap opens it. No picture: a project
                    // has none, and the adder's would suggest a message from
                    // them that was never written.
                    new NotificationTarget(event.projectId().toString(), null),
                    // The type's own fixed sentence — never the project's name,
                    // because this line renders on a lock screen (ADR-025
                    // decision 6).
                    NotificationType.AX_RENEWAL_MEMBER_ADDED.body());
        } catch (RuntimeException e) {
            log.error(
                    "could not tell account {} they were added to AX Renewal project {}",
                    event.accountId(),
                    event.projectId(),
                    e);
        }
    }
}
