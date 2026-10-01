package com.coc.docs.notify;

import com.coc.docs.axrenewal.AxRenewalMemberRepository;
import com.coc.docs.axrenewal.AxRenewalRunReviewedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Tells the person who started an implementation run how it was reviewed
 * (ADR-081).
 *
 * <p>The same shape as {@link AxRenewalMemberNotifier}: {@code AFTER_COMMIT},
 * because an approve or reject that rolled back decided nothing, and {@code
 * @Async}, so a slow push service never sits inside the reviewer's request.
 * Both are always transactional, so there is no fallback for a missing
 * transaction.
 *
 * <p><b>Who:</b> the starter, and only when somebody else made the decision
 * — nobody needs telling what they just did themselves — and only while the
 * starter is still a member: a removed member is not sent to a project they
 * can no longer open. Nothing is rethrown; the outcome stands whether or not
 * anybody hears about it.
 *
 * <p>// req: ADR-081, ADR-075, ADR-034, ADR-041
 */
@Component
public class AxRenewalRunReviewedNotifier {

    private static final Logger log = LoggerFactory.getLogger(AxRenewalRunReviewedNotifier.class);

    private final NotificationService notifications;
    private final AxRenewalMemberRepository members;

    public AxRenewalRunReviewedNotifier(NotificationService notifications, AxRenewalMemberRepository members) {
        this.notifications = notifications;
        this.members = members;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRunReviewed(AxRenewalRunReviewedEvent event) {
        if (event.startedBy() == null || event.startedBy().equals(event.reviewedBy())) {
            return;
        }
        try {
            if (!members.isMember(event.projectId(), event.startedBy())) {
                return;
            }
            notifications.notifyAccount(
                    event.startedBy(),
                    NotificationType.AX_RENEWAL_RUN_REVIEWED,
                    // The project: its run history says who reviewed it, and when.
                    new NotificationTarget(event.projectId().toString(), null),
                    // The outcome, as a fixed sentence — never the project's
                    // name or the reviewer's (ADR-025 decision 6).
                    event.approved() ? NotificationType.RUN_APPROVED_BODY : NotificationType.RUN_REJECTED_BODY);
        } catch (RuntimeException e) {
            log.error("could not tell account {} how AX Renewal run {} was reviewed",
                    event.startedBy(), event.runId(), e);
        }
    }
}
