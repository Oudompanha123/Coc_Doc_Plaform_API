package com.coc.docs.notify;

import com.coc.docs.account.Account;
import com.coc.docs.account.AccountRepository;
import com.coc.docs.account.AppRole;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Decides who hears about something, and makes sure the destinations stay
 * honest afterwards.
 *
 * <p>Sits between the thing that happened and the transport. Publishers know
 * nothing about tokens; {@link PushSender} knows nothing about roles.
 *
 * <p>// req: FR-AUTH-008, ADR-025
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final AccountRepository accounts;
    private final DeviceTokenRepository devices;
    private final PushSender push;

    public NotificationService(
            AccountRepository accounts, DeviceTokenRepository devices, PushSender push) {
        this.accounts = accounts;
        this.devices = devices;
        this.push = push;
    }

    /**
     * Notifies everyone who can act on an approval.
     *
     * <p>Resolved by query at send time rather than from a stored subscriber
     * list: promoting somebody is then enough to start notifying them, and
     * there is no second copy of "who may approve" to drift out of step.
     *
     * <p>Deliberately one method. ADR-016 exists so that this might one day be
     * a permission (`account.approve`) rather than a role, and when it is, this
     * is the only place that changes.
     */
    @Transactional
    public void notifyApprovers(NotificationType type, NotificationTarget target) {
        Set<UUID> recipients = accounts.findActiveByRole(AppRole.SUPER_ADMIN).stream()
                .map(Account::id)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());

        if (recipients.isEmpty()) {
            // Only reachable in a half-built installation: the V4 trigger keeps
            // at least one active super_admin, and the bootstrap makes the first
            // account one. Worth a line rather than a silent return.
            log.warn("no active super_admin to notify about {}", type.key());
            return;
        }

        List<String> tokens =
                devices.findByAccountIds(recipients).stream().map(DeviceToken::token).toList();
        if (tokens.isEmpty()) {
            // Normal, not a fault: nobody has granted notification permission
            // yet. The pending queue is still the authority on who is waiting.
            log.debug("no registered devices for {} approver(s)", recipients.size());
            return;
        }

        List<String> dead = push.send(tokens, type, target);
        if (!dead.isEmpty()) {
            devices.deleteAll(dead);
        }
    }
}
