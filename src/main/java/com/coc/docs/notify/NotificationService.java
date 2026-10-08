package com.coc.docs.notify;

import com.coc.docs.account.Account;
import com.coc.docs.account.AccountRepository;
import com.coc.docs.account.AppRole;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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

    /**
     * The open browser connections. Sent to before the push, because it is the
     * transport that always exists: Firebase is optional and mostly absent,
     * this is an HTTP response.
     */
    private final NotificationStream stream;

    /**
     * Where a notification is kept, as opposed to where it is sent.
     *
     * <p>Written before any transport is attempted, so a failed push or a
     * stream nobody is connected to costs a prompt rather than the record
     * (ADR-034).
     */
    private final NotificationRepository notifications;

    /**
     * The caller's own opt-outs (ADR-041). Checked before {@link
     * NotificationRepository#insertFor}, not just before delivery — the
     * {@code notification} table is the bell (ADR-034), so an opted-out
     * recipient must not see the row either, not merely not be pushed to.
     */
    private final NotificationPreferenceRepository preferences;

    private final ObjectMapper json;

    public NotificationService(
            AccountRepository accounts,
            DeviceTokenRepository devices,
            PushSender push,
            NotificationStream stream,
            NotificationRepository notifications,
            NotificationPreferenceRepository preferences,
            ObjectMapper json) {
        this.accounts = accounts;
        this.devices = devices;
        this.push = push;
        this.stream = stream;
        this.notifications = notifications;
        this.preferences = preferences;
        this.json = json;
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

        // ADR-041: an approver who opted out of this type sees nothing and is
        // sent nothing — filtered before the row is written, not after, since
        // the notification table is the bell they'd otherwise see it in.
        recipients = preferences.filterEnabled(recipients, type);
        if (recipients.isEmpty()) {
            log.debug("every super_admin opted out of {}", type.key());
            return;
        }

        // Before the push, and before the early return below. A tab that is
        // open is updated whether or not anybody has ever granted notification
        // permission — which for most of this team is the only thing that
        // happens at all.
        // Persisted before anything is transmitted, in the transaction that
        // carries the event. Delivery below is best-effort — a stream nobody
        // is connected to, a push to a device that has gone — and none of it
        // can now lose the notification, because the record already exists
        // (ADR-034 decision 5).
        int stored = notifications.insertFor(recipients, type, target.id(), payloadOf(target, type.body()));
        // The one line that says the notification exists. Everything after this
        // is delivery, and delivery failing is survivable; this failing is not,
        // and it used to be invisible.
        log.info("stored {} {} notification(s) for {} approver(s)", stored, type.key(), recipients.size());

        afterCommit(recipients, type, target, null, null);
    }

    /**
     * Notifies one account about something that happened to it.
     *
     * <p>The counterpart to {@link #notifyApprovers}: there the recipients are
     * whoever holds a role, here the recipient is the subject. A new-device
     * sign-in is nobody else's business — not an administrator's either, which
     * is why this does not quietly widen to super admins the way an
     * "interesting security event" notification tends to.
     *
     * <p>Every one of the account's registered devices is sent to, including —
     * unavoidably — the one that just signed in, because a device token and a
     * session are not the same thing and cannot be matched up. Being asked
     * "was this you?" on the device you are holding is a poor banner and a
     * harmless one; failing to ask the other five would be the real fault.
     *
     * <p>// req: FR-AUTH-007, ADR-025, ADR-027
     */
    @Transactional
    public void notifyAccount(
            UUID accountId, NotificationType type, NotificationTarget target, String body) {
        // ADR-041: checked before anything is written or sent. Includes
        // NEW_DEVICE_SIGN_IN — a PM decision recorded in ADR-041, since that
        // alert is otherwise the one thing a compromised account could not
        // silence for itself.
        if (!preferences.isEnabled(accountId, type)) {
            log.debug("account {} opted out of {}", accountId, type.key());
            return;
        }

        // Persisted first, in the transaction that carries the event —
        // everything below is best-effort delivery and can no longer lose it.
        int stored = notifications.insertFor(List.of(accountId), type, target.id(), payloadOf(target, body));
        log.info("stored {} {} notification(s) for account {}", stored, type.key(), accountId);

        afterCommit(Set.of(accountId), type, target, body, null);
    }

    /**
     * Notifies every active account but {@code except} — the broadcast an
     * announcement needs (ADR-098).
     *
     * <p>Recipients are resolved at send time from the same "can be written
     * to" query the attendance mail uses, so a deactivated account or one
     * still awaiting approval is not sent a company announcement. Opt-outs
     * (ADR-041) are honoured exactly as for every other type.
     *
     * <p>{@code facts} are added to the stored payload beside the fixed
     * {@code body}; they are read by the signed-in recipient only. The push
     * itself carries the type's fixed sentence and nothing from {@code facts},
     * because a push renders on a lock screen — unless the caller passes its
     * own {@link PushBanner} to the overload below, which an announcement does
     * (ADR-103).
     *
     * @param except the account that caused it, who is not told about their
     *     own action; null to include everybody
     * @return how many accounts it was stored for
     */
    @Transactional
    public int notifyActiveAccounts(
            NotificationType type, NotificationTarget target, Map<String, String> facts, UUID except) {
        return notifyActiveAccounts(type, target, facts, except, null);
    }

    /**
     * As {@link #notifyActiveAccounts(NotificationType, NotificationTarget, Map, UUID)},
     * with the push banner's title, text and picture chosen by the caller
     * rather than the type's fixed wording (ADR-103).
     *
     * @param banner what the push shows, or null for the type's own title and sentence
     */
    @Transactional
    public int notifyActiveAccounts(
            NotificationType type,
            NotificationTarget target,
            Map<String, String> facts,
            UUID except,
            PushBanner banner) {
        Set<UUID> recipients = accounts.activeMailTargets().stream()
                .map(AccountRepository.MailTarget::accountId)
                .filter(id -> !id.equals(except))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        recipients = preferences.filterEnabled(recipients, type);
        if (recipients.isEmpty()) {
            log.debug("nobody to notify about {}", type.key());
            return 0;
        }

        ObjectNode payload = payloadOf(target, type.body());
        facts.forEach(payload::put);

        // Persisted first, in the transaction that carries the event, as in
        // notifyApprovers -- delivery below is best-effort.
        int stored = notifications.insertFor(recipients, type, target.id(), payload);
        log.info("stored {} {} notification(s) for {} account(s)", stored, type.key(), recipients.size());

        afterCommit(recipients, type, target, null, banner);
        return stored;
    }

    /**
     * Delivers what was just stored — the stream nudge to open tabs, then the
     * push — once the transaction that stored it has committed.
     *
     * <p><b>Why not straight away.</b> Every method here is {@code @Transactional},
     * so the rows are invisible to anybody else until it returns. Delivering
     * inline nudged each open tab first; the tab re-read its inbox within
     * milliseconds, found nothing new (the commit was still behind the
     * ~half-second push call), and showed no banner until its next minute's
     * poll. A member watching the page saw nothing — reported on an
     * announcement (ADR-098), but true of every type. A push tapped in that
     * window opened an inbox without its row, too. {@link NotificationStream}
     * has always documented that it is called after the commit; this makes it
     * so.
     *
     * <p>Outside a transaction (a unit test, a caller with none) it runs at
     * once, which is the same thing.
     *
     * @param body the banner text, or null for the type's own sentence
     * @param banner the whole banner, which wins over {@code body}; null for none
     */
    private void afterCommit(
            Set<UUID> recipients, NotificationType type, NotificationTarget target, String body, PushBanner banner) {
        Runnable delivery = () -> deliver(recipients, type, target, body, banner);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    delivery.run();
                }
            });
        } else {
            delivery.run();
        }
    }

    /**
     * The best-effort half. The record already exists (ADR-034 decision 5), so
     * nothing here can lose it, and nothing here is allowed to throw back into
     * a caller whose work has already committed.
     */
    private void deliver(
            Set<UUID> recipients, NotificationType type, NotificationTarget target, String body, PushBanner banner) {
        try {
            // Before the push and before the early return below: an open tab
            // is updated whether or not anybody has ever granted notification
            // permission, which for most of this team is the only thing that
            // happens at all.
            recipients.forEach(id -> stream.publish(id, type));

            List<String> tokens =
                    devices.findByAccountIds(recipients).stream().map(DeviceToken::token).toList();
            if (tokens.isEmpty()) {
                // Normal, not a fault: nobody has granted notification
                // permission on any device yet. The row is in the bell.
                log.debug("no registered devices for {} recipient(s) of {}", recipients.size(), type.key());
                return;
            }

            List<String> dead = banner != null
                    ? push.sendBanner(tokens, type, target, banner)
                    : body == null ? push.send(tokens, type, target) : push.send(tokens, type, target, body);
            if (!dead.isEmpty()) {
                devices.deleteAll(dead);
            }
        } catch (RuntimeException e) {
            log.error("could not deliver {} to {} recipient(s); it is still in their bell", type.key(), recipients.size(), e);
        }
    }

    /**
     * The facts a type needs to render its sentence, and no more of them.
     *
     * <p>The wording itself is not stored (ADR-034 decision 2): a row carries
     * what happened, and the message is built from it at read time, so
     * improving a sentence applies to everything already sent.
     *
     * <p><b>What goes in here is readable by the recipient</b>, so it holds no
     * personal data beyond what the notification must say (NFR-SEC-PII). The
     * device of a sign-in, yes — it is a browser and an operating system, and
     * it is the thing that lets somebody answer "was that me". An applicant's
     * name, no: the queue has it, behind the authorisation that belongs on it.
     *
     * @param body the sentence the sender would have used. Stored as
     *     {@code body} so a client that does not know this type — an older
     *     build, or one after a type was retired — still has something true to
     *     show rather than a blank row.
     */
    private ObjectNode payloadOf(NotificationTarget target, String body) {
        var payload = json.createObjectNode().put("body", body);
        if (target.iconUrl() != null) {
            // A picture the client already shows in the bell. Not identity: a
            // URL the browser will fetch anyway to render the row.
            payload.put("iconUrl", target.iconUrl());
        }
        return payload;
    }
}
