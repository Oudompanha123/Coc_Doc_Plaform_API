package com.coc.docs.notify;

import com.coc.docs.axrenewal.AxRenewalCommentAddedEvent;
import com.coc.docs.axrenewal.AxRenewalMember;
import com.coc.docs.axrenewal.AxRenewalMemberRepository;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Tells a project's members that somebody commented on it (ADR-080).
 *
 * <p>The same shape as {@link AxRenewalMemberNotifier}: {@code AFTER_COMMIT},
 * because a comment that rolled back must tell nobody, and {@code @Async}, so
 * a slow push service never sits inside the request of whoever pressed Send.
 * Posting a comment is always transactional, so unlike {@link
 * AxRenewalRunReadyNotifier} there is no fallback for a missing transaction.
 *
 * <p>Recipients are every member but the author, read here after the commit.
 * One recipient's failure is logged and the rest are still told; nothing is
 * rethrown, because the comment exists whether or not anybody hears about it.
 *
 * <p>// req: ADR-080, ADR-075, ADR-034, ADR-041
 */
@Component
public class AxRenewalCommentNotifier {

    private static final Logger log = LoggerFactory.getLogger(AxRenewalCommentNotifier.class);

    private final NotificationService notifications;
    private final AxRenewalMemberRepository members;

    public AxRenewalCommentNotifier(NotificationService notifications, AxRenewalMemberRepository members) {
        this.notifications = notifications;
        this.members = members;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCommentAdded(AxRenewalCommentAddedEvent event) {
        List<AxRenewalMember> recipients;
        try {
            recipients = members.listForProject(event.projectId());
        } catch (RuntimeException e) {
            log.error("could not read the members of AX Renewal project {} to tell them about comment {}",
                    event.projectId(), event.commentId(), e);
            return;
        }
        for (AxRenewalMember member : recipients) {
            // Nobody needs telling about their own comment.
            if (member.accountId().equals(event.authorId())) {
                continue;
            }
            try {
                notifications.notifyAccount(
                        member.accountId(),
                        NotificationType.AX_RENEWAL_COMMENT_ADDED,
                        // The project: its comment thread is on screen there.
                        new NotificationTarget(event.projectId().toString(), null),
                        // Never the comment's text or the project's name —
                        // lock screen (ADR-025 decision 6).
                        NotificationType.AX_RENEWAL_COMMENT_ADDED.body());
            } catch (RuntimeException e) {
                log.error("could not tell account {} about comment {} on AX Renewal project {}",
                        member.accountId(), event.commentId(), event.projectId(), e);
            }
        }
    }
}
