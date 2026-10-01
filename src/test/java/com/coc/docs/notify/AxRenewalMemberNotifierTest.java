package com.coc.docs.notify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.coc.docs.axrenewal.AxRenewalMemberAddedEvent;
import java.lang.reflect.Method;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Telling somebody they were added to an AX Renewal project (ADR-075).
 *
 * <p>Delivery itself — opt-outs, the stored bell row, the stream, push — is
 * {@link NotificationService#notifyAccount}'s, covered by {@code
 * NotificationServiceTest}. This covers that the right person is told the
 * right thing, at the right time, and that a failure stays contained.
 */
class AxRenewalMemberNotifierTest {

    private final NotificationService notifications = mock(NotificationService.class);
    private final AxRenewalMemberNotifier notifier = new AxRenewalMemberNotifier(notifications);

    private final UUID projectId = UUID.randomUUID();
    private final UUID addedAccount = UUID.randomUUID();

    @Test
    void tells_the_account_that_was_added_and_points_at_the_project() {
        notifier.onMemberAdded(new AxRenewalMemberAddedEvent(projectId, addedAccount));

        ArgumentCaptor<NotificationTarget> target = ArgumentCaptor.forClass(NotificationTarget.class);
        verify(notifications)
                .notifyAccount(
                        eq(addedAccount),
                        eq(NotificationType.AX_RENEWAL_MEMBER_ADDED),
                        target.capture(),
                        anyString());
        assertThat(target.getValue().id()).isEqualTo(projectId.toString());
        assertThat(target.getValue().iconUrl()).isNull();
    }

    @Test
    void says_only_the_fixed_sentence_never_the_project_s_name() {
        // The body renders on a lock screen (ADR-025 decision 6).
        notifier.onMemberAdded(new AxRenewalMemberAddedEvent(projectId, addedAccount));

        verify(notifications)
                .notifyAccount(any(), any(), any(), eq(NotificationType.AX_RENEWAL_MEMBER_ADDED.body()));
    }

    @Test
    void a_delivery_failure_is_logged_never_thrown() {
        // The member row and its audit entry already exist; a failed prompt
        // must not surface as an error to anybody.
        doThrow(new IllegalStateException("push service down"))
                .when(notifications)
                .notifyAccount(any(), any(), any(), any());

        assertThatCode(() -> notifier.onMemberAdded(new AxRenewalMemberAddedEvent(projectId, addedAccount)))
                .doesNotThrowAnyException();
    }

    @Test
    void runs_after_commit_and_off_the_request_thread() throws NoSuchMethodException {
        // A rolled-back add must notify nobody, and a slow push service must
        // not sit inside the request of whoever pressed "Add member" — the
        // same two guarantees SignInNotifier relies on.
        Method handler = AxRenewalMemberNotifier.class.getMethod("onMemberAdded", AxRenewalMemberAddedEvent.class);

        assertThat(handler.getAnnotation(Async.class)).isNotNull();
        TransactionalEventListener listener = handler.getAnnotation(TransactionalEventListener.class);
        assertThat(listener).isNotNull();
        assertThat(listener.phase()).isEqualTo(TransactionPhase.AFTER_COMMIT);
    }
}
