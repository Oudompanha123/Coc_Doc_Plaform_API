package com.coc.docs.notify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.coc.docs.account.Account;
import com.coc.docs.account.AccountRepository;
import com.coc.docs.account.AccountStatus;
import com.coc.docs.account.AppRole;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Who gets told, and what happens to destinations that turn out to be dead.
 *
 * <p>Mocked rather than run against a database on purpose: every claim here is
 * about the decisions this class makes — which accounts to resolve, when not to
 * call the transport at all, what to do with the tokens FCM rejects. The
 * queries themselves are the repository's to get right, and a real database
 * would not make any of these assertions stronger.
 *
 * <p>// req: FR-AUTH-008, ADR-025
 */
class NotificationServiceTest {

    private final AccountRepository accounts = mock(AccountRepository.class);
    private final DeviceTokenRepository devices = mock(DeviceTokenRepository.class);
    private final PushSender push = mock(PushSender.class);
    // Real, not a mock: it holds a map of open connections and nothing else, so
    // a mock would only test that a mock was called. With no tab connected,
    // publish is a no-op, which is exactly the state these cases are in.
    private final NotificationStream stream = new NotificationStream();
    // Mocked: this class is about who is chosen and what the transport is
    // asked to do. Whether a row lands is NotificationPersistenceTest's
    // claim, and it needs a real database to make it.
    private final NotificationRepository notifications = mock(NotificationRepository.class);
    // Mocked, with every recipient passed straight through by default: these
    // tests are about who is resolved and what the transport is asked to do,
    // and ADR-041 opt-outs are NotificationPreferenceRepositoryIntegrationTest's
    // claim against a real database, not this class's.
    private final NotificationPreferenceRepository preferences = mock(NotificationPreferenceRepository.class);

    private final NotificationService service = new NotificationService(
                    accounts,
                    devices,
                    push,
                    stream,
                    notifications,
                    preferences,
                    new com.fasterxml.jackson.databind.ObjectMapper());

    {
        when(preferences.filterEnabled(any(), any())).thenAnswer(inv -> inv.getArgument(0));
        when(preferences.isEnabled(any(), any())).thenReturn(true);
    }

    private static Account approver(String email) {
        return new Account(
                UUID.randomUUID(),
                email,
                "Approver",
                EnumSet.of(AppRole.SUPER_ADMIN),
                null,
                null,
                AccountStatus.ACTIVE,
                null,
                null,
                Instant.now(),
                Instant.now(),
                false,
                true);
    }

    private static DeviceToken device(UUID accountId, String token) {
        return new DeviceToken(
                token, accountId, DevicePlatform.WEB, "test-agent", Instant.now(), Instant.now());
    }

    @Test
    @DisplayName("sends to every device belonging to every active super admin")
    void sendsToEveryApproverDevice() {
        Account first = approver("one@example.com");
        Account second = approver("two@example.com");
        when(accounts.findActiveByRole(AppRole.SUPER_ADMIN)).thenReturn(List.of(first, second));
        when(devices.findByAccountIds(any()))
                .thenReturn(List.of(
                        device(first.id(), "token-laptop"),
                        device(first.id(), "token-home"),
                        device(second.id(), "token-phone")));
        when(push.send(anyCollection(), any(), any())).thenReturn(List.of());

        service.notifyApprovers(NotificationType.NEW_REGISTRATION, NotificationTarget.none());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.Collection<String>> tokens =
                ArgumentCaptor.forClass(java.util.Collection.class);
        verify(push).send(tokens.capture(), any(), any());
        // One person, several devices: notifying the account is not the same as
        // notifying one browser.
        assertThat(tokens.getValue())
                .containsExactlyInAnyOrder("token-laptop", "token-home", "token-phone");
    }

    @Test
    @DisplayName("resolves recipients by role at send time, not from a stored list")
    void resolvesRecipientsFromTheRole() {
        Account only = approver("boss@example.com");
        when(accounts.findActiveByRole(AppRole.SUPER_ADMIN)).thenReturn(List.of(only));
        when(devices.findByAccountIds(any())).thenReturn(List.of(device(only.id(), "t")));
        when(push.send(anyCollection(), any(), any())).thenReturn(List.of());

        service.notifyApprovers(NotificationType.NEW_REGISTRATION, NotificationTarget.none());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Set<UUID>> ids = ArgumentCaptor.forClass(Set.class);
        verify(devices).findByAccountIds(ids.capture());
        // Promoting somebody is enough to start notifying them: there is no
        // second copy of "who may approve" that could drift out of step.
        assertThat(ids.getValue()).containsExactly(only.id());
        verify(accounts).findActiveByRole(AppRole.SUPER_ADMIN);
    }

    /**
     * A token FCM has retired must be deleted, or it stays in the table forever
     * and every later send spends work on an address nobody is listening to.
     */
    @Test
    @DisplayName("deletes the tokens FCM reports dead")
    void prunesDeadTokens() {
        Account boss = approver("boss@example.com");
        when(accounts.findActiveByRole(AppRole.SUPER_ADMIN)).thenReturn(List.of(boss));
        when(devices.findByAccountIds(any()))
                .thenReturn(List.of(device(boss.id(), "alive"), device(boss.id(), "retired")));
        when(push.send(anyCollection(), any(), any())).thenReturn(List.of("retired"));

        service.notifyApprovers(NotificationType.NEW_REGISTRATION, NotificationTarget.none());

        verify(devices).deleteAll(List.of("retired"));
    }

    @Test
    @DisplayName("deletes nothing when every token was accepted")
    void keepsLiveTokens() {
        Account boss = approver("boss@example.com");
        when(accounts.findActiveByRole(AppRole.SUPER_ADMIN)).thenReturn(List.of(boss));
        when(devices.findByAccountIds(any())).thenReturn(List.of(device(boss.id(), "alive")));
        when(push.send(anyCollection(), any(), any())).thenReturn(List.of());

        service.notifyApprovers(NotificationType.NEW_REGISTRATION, NotificationTarget.none());

        // A transport failure returns no dead tokens precisely so that a blip
        // in the network cannot unsubscribe people.
        verify(devices, never()).deleteAll(anyCollection());
    }

    @Test
    @DisplayName("does not call the transport when nobody has registered a device")
    void noDevicesMeansNoSend() {
        when(accounts.findActiveByRole(AppRole.SUPER_ADMIN))
                .thenReturn(List.of(approver("boss@example.com")));
        when(devices.findByAccountIds(any())).thenReturn(List.of());

        service.notifyApprovers(NotificationType.NEW_REGISTRATION, NotificationTarget.none());

        // Normal, not a fault: nobody has granted notification permission yet.
        verifyNoInteractions(push);
    }

    @Test
    @DisplayName("does not look for devices when there is no active super admin")
    void noApproversMeansNoLookup() {
        when(accounts.findActiveByRole(AppRole.SUPER_ADMIN)).thenReturn(List.of());

        service.notifyApprovers(NotificationType.NEW_REGISTRATION, NotificationTarget.none());

        verifyNoInteractions(devices);
        verifyNoInteractions(push);
    }

    @Test
    @DisplayName("an announcement reaches every active account but its publisher, title in the bell and not on the lock screen (ADR-098)")
    void announcementBroadcastSkipsThePublisher() {
        UUID publisher = UUID.randomUUID();
        UUID reader = UUID.randomUUID();
        when(accounts.activeMailTargets())
                .thenReturn(List.of(
                        new AccountRepository.MailTarget(publisher, "admin@example.com", null),
                        new AccountRepository.MailTarget(reader, "reader@example.com", null)));
        when(notifications.insertFor(anyCollection(), any(), any(), any())).thenReturn(1);
        when(devices.findByAccountIds(any())).thenReturn(List.of(device(reader, "token-reader")));
        when(push.send(anyCollection(), any(), any())).thenReturn(List.of());

        int stored = service.notifyActiveAccounts(
                NotificationType.ANNOUNCEMENT_PUBLISHED,
                new NotificationTarget("a1", null),
                java.util.Map.of("title", "Maintenance on Friday"),
                publisher);

        assertThat(stored).isEqualTo(1);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.Collection<UUID>> recipients = ArgumentCaptor.forClass(java.util.Collection.class);
        ArgumentCaptor<com.fasterxml.jackson.databind.JsonNode> payload =
                ArgumentCaptor.forClass(com.fasterxml.jackson.databind.JsonNode.class);
        verify(notifications).insertFor(recipients.capture(), any(), any(), payload.capture());
        assertThat(recipients.getValue()).containsExactly(reader);
        assertThat(payload.getValue().get("title").asText()).isEqualTo("Maintenance on Friday");
        assertThat(payload.getValue().get("body").asText()).isEqualTo(NotificationType.ANNOUNCEMENT_PUBLISHED.body());
        // The three-argument send: the type's fixed sentence, never the title.
        verify(push).send(anyCollection(), any(), any());
    }

    @Test
    @DisplayName("an announcement with nobody left to tell stores and sends nothing")
    void announcementWithNoRecipients() {
        UUID publisher = UUID.randomUUID();
        when(accounts.activeMailTargets())
                .thenReturn(List.of(new AccountRepository.MailTarget(publisher, "admin@example.com", null)));

        int stored = service.notifyActiveAccounts(
                NotificationType.ANNOUNCEMENT_PUBLISHED, new NotificationTarget("a1", null), java.util.Map.of(), publisher);

        assertThat(stored).isZero();
        verify(notifications, never()).insertFor(anyCollection(), any(), any(), any());
        verifyNoInteractions(push);
    }

    @Test
    @DisplayName("nothing is nudged or pushed until the storing transaction commits")
    void deliversOnlyAfterCommit() {
        // The regression: delivery inside the transaction nudged open tabs
        // before the row was visible, so they re-read an inbox without it and
        // showed no banner (ADR-098, reported on an announcement).
        UUID reader = UUID.randomUUID();
        when(accounts.activeMailTargets())
                .thenReturn(List.of(new AccountRepository.MailTarget(reader, "reader@example.com", null)));
        when(notifications.insertFor(anyCollection(), any(), any(), any())).thenReturn(1);
        when(devices.findByAccountIds(any())).thenReturn(List.of(device(reader, "token-reader")));
        when(push.send(anyCollection(), any(), any())).thenReturn(List.of());

        org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
        try {
            service.notifyActiveAccounts(
                    NotificationType.ANNOUNCEMENT_PUBLISHED, new NotificationTarget("a1", null), java.util.Map.of(), null);

            verify(notifications).insertFor(anyCollection(), any(), any(), any());
            verifyNoInteractions(push);

            org.springframework.transaction.support.TransactionSynchronizationManager.getSynchronizations()
                    .forEach(org.springframework.transaction.support.TransactionSynchronization::afterCommit);
        } finally {
            org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization();
        }

        verify(push).send(anyCollection(), any(), any());
    }

    @Test
    @DisplayName("a push that fails after the commit is logged, never thrown at a caller whose work is saved")
    void deliveryFailureIsSwallowed() {
        UUID reader = UUID.randomUUID();
        when(devices.findByAccountIds(any())).thenReturn(List.of(device(reader, "token-reader")));
        when(push.send(anyCollection(), any(), any(), any())).thenThrow(new IllegalStateException("fcm down"));

        org.assertj.core.api.Assertions.assertThatCode(() -> service.notifyAccount(
                        reader, NotificationType.NEW_DEVICE_SIGN_IN, NotificationTarget.none(), "Chrome on Windows"))
                .doesNotThrowAnyException();
    }
}
