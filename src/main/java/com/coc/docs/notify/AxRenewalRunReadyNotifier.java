package com.coc.docs.notify;

import com.coc.docs.axrenewal.AxRenewalMember;
import com.coc.docs.axrenewal.AxRenewalMemberRepository;
import com.coc.docs.axrenewal.AxRenewalRunReadyEvent;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Tells a project's members that an implementation run is ready for review
 * (ADR-079).
 *
 * <p>The same shape as {@link AxRenewalMemberNotifier}, with one addition:
 * <b>{@code fallbackExecution = true}</b>. A generated run reaches {@code
 * awaiting_review} on the runner's own thread, in a single auto-committed
 * update with no surrounding transaction — and a plain {@code AFTER_COMMIT}
 * listener silently drops an event published outside one. With the fallback
 * it runs at once there, the update having already committed; a replay is
 * published inside {@code startImplementation}'s transaction and still waits
 * for its commit.
 *
 * <p>Recipients are read here, after the commit, so a member removed in the
 * meantime is not told. One recipient's failure is logged and the rest are
 * still told; nothing is rethrown — the run is ready whether or not anybody
 * hears about it.
 *
 * <p>// req: ADR-079, ADR-075, ADR-034, ADR-041
 */
@Component
public class AxRenewalRunReadyNotifier {

    private static final Logger log = LoggerFactory.getLogger(AxRenewalRunReadyNotifier.class);

    private final NotificationService notifications;
    private final AxRenewalMemberRepository members;

    public AxRenewalRunReadyNotifier(NotificationService notifications, AxRenewalMemberRepository members) {
        this.notifications = notifications;
        this.members = members;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onRunReady(AxRenewalRunReadyEvent event) {
        List<AxRenewalMember> recipients;
        try {
            recipients = members.listForProject(event.projectId());
        } catch (RuntimeException e) {
            log.error("could not read the members of AX Renewal project {} to tell them run {} is ready",
                    event.projectId(), event.runId(), e);
            return;
        }
        for (AxRenewalMember member : recipients) {
            if (member.accountId().equals(event.skipAccountId())) {
                continue;
            }
            try {
                notifications.notifyAccount(
                        member.accountId(),
                        NotificationType.AX_RENEWAL_RUN_READY,
                        // The project, so a tap opens it on the run awaiting review.
                        new NotificationTarget(event.projectId().toString(), null),
                        // Never the project's name — lock screen (ADR-025 decision 6).
                        NotificationType.AX_RENEWAL_RUN_READY.body());
            } catch (RuntimeException e) {
                log.error("could not tell account {} that AX Renewal run {} is ready for review",
                        member.accountId(), event.runId(), e);
            }
        }
    }
}
