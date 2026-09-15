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

    private final NotificationService service = new NotificationService(accounts, devices, push);

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
                Instant.now(),
                Instant.now(),
                false);
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
}
