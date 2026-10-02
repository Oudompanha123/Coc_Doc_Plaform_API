package com.coc.docs.notify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.coc.docs.axrenewal.AxRenewalMemberRepository;
import com.coc.docs.axrenewal.AxRenewalRunReviewedEvent;
import java.lang.reflect.Method;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Telling a run's starter how it was reviewed (ADR-081).
 *
 * <p>Delivery itself — opt-outs, the stored bell row, the stream, push — is
 * {@link NotificationService#notifyAccount}'s, covered by {@code
 * NotificationServiceTest}. This covers who is told, what, and when.
 */
class AxRenewalRunReviewedNotifierTest {

    private final NotificationService notifications = mock(NotificationService.class);
    private final AxRenewalMemberRepository members = mock(AxRenewalMemberRepository.class);
    private final AxRenewalRunReviewedNotifier notifier = new AxRenewalRunReviewedNotifier(notifications, members);

    private final UUID projectId = UUID.randomUUID();
    private final UUID runId = UUID.randomUUID();
    private final UUID starter = UUID.randomUUID();
    private final UUID reviewer = UUID.randomUUID();

    @BeforeEach
    void starterIsOnTheProject() {
        when(members.isMember(projectId, starter)).thenReturn(true);
    }

    private AxRenewalRunReviewedEvent reviewed(UUID by, boolean approved) {
        return new AxRenewalRunReviewedEvent(projectId, runId, starter, by, approved);
    }

    @Test
    void tells_the_starter_their_run_was_approved_and_applied() {
        notifier.onRunReviewed(reviewed(reviewer, true));

        verify(notifications)
                .notifyAccount(
                        eq(starter),
                        eq(NotificationType.AX_RENEWAL_RUN_REVIEWED),
                        any(),
                        eq("Your AX Renewal implementation run was approved and applied"));
    }

    @Test
    void tells_the_starter_their_run_was_rejected() {
        notifier.onRunReviewed(reviewed(reviewer, false));

        verify(notifications)
                .notifyAccount(
                        eq(starter),
                        eq(NotificationType.AX_RENEWAL_RUN_REVIEWED),
                        any(),
                        eq("Your AX Renewal implementation run was rejected"));
    }

    @Test
    void tells_only_the_starter() {
        notifier.onRunReviewed(reviewed(reviewer, true));

        verify(notifications, times(1)).notifyAccount(any(), any(), any(), any());
    }

    @Test
    void tells_nobody_about_their_own_decision() {
        notifier.onRunReviewed(reviewed(starter, true));
        notifier.onRunReviewed(reviewed(starter, false));

        verifyNoInteractions(notifications);
    }

    @Test
    void tells_nobody_when_the_starter_has_left_the_project() {
        when(members.isMember(projectId, starter)).thenReturn(false);

        notifier.onRunReviewed(reviewed(reviewer, true));

        verifyNoInteractions(notifications);
    }

    @Test
    void tells_nobody_when_the_starter_is_unknown() {
        notifier.onRunReviewed(new AxRenewalRunReviewedEvent(projectId, runId, null, reviewer, true));

        verifyNoInteractions(notifications, members);
    }

    @Test
    void points_at_the_project_and_names_neither_it_nor_the_reviewer() {
        notifier.onRunReviewed(reviewed(reviewer, true));

        ArgumentCaptor<NotificationTarget> target = ArgumentCaptor.forClass(NotificationTarget.class);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(notifications).notifyAccount(any(), any(), target.capture(), body.capture());
        assertThat(target.getValue().id()).isEqualTo(projectId.toString());
        assertThat(target.getValue().iconUrl()).isNull();
        // One of the two fixed sentences, nothing interpolated (ADR-025 decision 6).
        assertThat(body.getValue()).isIn(NotificationType.RUN_APPROVED_BODY, NotificationType.RUN_REJECTED_BODY);
    }

    @Test
    void a_delivery_failure_is_logged_never_thrown() {
        doThrow(new IllegalStateException("push service down"))
                .when(notifications)
                .notifyAccount(any(), any(), any(), any());

        assertThatCode(() -> notifier.onRunReviewed(reviewed(reviewer, true))).doesNotThrowAnyException();
    }

    @Test
    void a_failure_to_check_membership_is_logged_never_thrown() {
        when(members.isMember(projectId, starter)).thenThrow(new IllegalStateException("database down"));

        assertThatCode(() -> notifier.onRunReviewed(reviewed(reviewer, true))).doesNotThrowAnyException();
        verifyNoInteractions(notifications);
    }

    @Test
    void runs_after_commit_and_off_the_request_thread() throws NoSuchMethodException {
        Method handler = AxRenewalRunReviewedNotifier.class.getMethod("onRunReviewed", AxRenewalRunReviewedEvent.class);

        assertThat(handler.getAnnotation(Async.class)).isNotNull();
        TransactionalEventListener listener = handler.getAnnotation(TransactionalEventListener.class);
        assertThat(listener).isNotNull();
        assertThat(listener.phase()).isEqualTo(TransactionPhase.AFTER_COMMIT);
    }
}
