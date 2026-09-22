package com.coc.docs.notify;

import com.coc.docs.account.Account;
import com.coc.docs.account.AccountRepository;
import com.coc.docs.account.AppRole;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

        recipients.forEach(id -> stream.publish(id, type));

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

        // Before the push, and before the early return below: an open tab is
        // updated whether or not this person has ever granted notification
        // permission, which for most of this team is the only thing that
        // happens at all.
        stream.publish(accountId, type);

        List<String> tokens = devices.findByAccountIds(Set.of(accountId)).stream()
                .map(DeviceToken::token)
                .toList();
        if (tokens.isEmpty()) {

            // Normal: this person has not granted notification permission on
            // any device. The alert is still in the bell and in the audit log.
            log.debug("no registered devices for account {} ({})", accountId, type.key());
            return;
        }

        List<String> dead = push.send(tokens, type, target, body);
        if (!dead.isEmpty()) {
            devices.deleteAll(dead);
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
    private JsonNode payloadOf(NotificationTarget target, String body) {
        var payload = json.createObjectNode().put("body", body);
        if (target.iconUrl() != null) {
            // A picture the client already shows in the bell. Not identity: a
            // URL the browser will fetch anyway to render the row.
            payload.put("iconUrl", target.iconUrl());
        }
        return payload;
    }
}
