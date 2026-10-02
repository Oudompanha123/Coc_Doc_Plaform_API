package com.coc.docs.notify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.coc.docs.axrenewal.AxRenewalMember;
import com.coc.docs.axrenewal.AxRenewalMemberRepository;
import com.coc.docs.axrenewal.AxRenewalRunReadyEvent;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Telling a project's members that a run is ready for review (ADR-079).
 *
 * <p>Delivery itself — opt-outs, the stored bell row, the stream, push — is
 * {@link NotificationService#notifyAccount}'s, covered by {@code
 * NotificationServiceTest}. This covers who is told, what they are told,
 * when, and that a failure stays contained.
 */
class AxRenewalRunReadyNotifierTest {

    private final NotificationService notifications = mock(NotificationService.class);
    private final AxRenewalMemberRepository members = mock(AxRenewalMemberRepository.class);
    private final AxRenewalRunReadyNotifier notifier = new AxRenewalRunReadyNotifier(notifications, members);

    private final UUID projectId = UUID.randomUUID();
    private final UUID runId = UUID.randomUUID();
    private final UUID starter = UUID.randomUUID();
    private final UUID reviewer = UUID.randomUUID();
    private final UUID lead = UUID.randomUUID();

    private AxRenewalMember member(UUID accountId, String name) {
        return new AxRenewalMember(projectId, accountId, name, Instant.EPOCH);
    }

    @BeforeEach
    void team() {
        when(members.listForProject(projectId))
                .thenReturn(List.of(member(starter, "Dara"), member(reviewer, "Sokha"), member(lead, "Vanna")));
    }

    @Test
    void tells_every_member_when_nobody_is_skipped() {
        notifier.onRunReady(new AxRenewalRunReadyEvent(projectId, runId, null));

        for (UUID account : List.of(starter, reviewer, lead)) {
            verify(notifications)
                    .notifyAccount(eq(account), eq(NotificationType.AX_RENEWAL_RUN_READY), any(), any());
        }
    }

    @Test
    void leaves_out_the_one_member_it_was_asked_to_skip() {
        // A replay's starter is looking at its result already.
        notifier.onRunReady(new AxRenewalRunReadyEvent(projectId, runId, starter));

        verify(notifications, never()).notifyAccount(eq(starter), any(), any(), any());
        verify(notifications, times(2)).notifyAccount(any(), eq(NotificationType.AX_RENEWAL_RUN_READY), any(), any());
    }

    @Test
    void tells_only_the_project_s_members() {
        // Recipients are the member list and nothing else — no role, no
        // super admin who merely could open the project.
        when(members.listForProject(projectId)).thenReturn(List.of(member(reviewer, "Sokha")));

        notifier.onRunReady(new AxRenewalRunReadyEvent(projectId, runId, null));

        verify(notifications).notifyAccount(eq(reviewer), any(), any(), any());
        verify(notifications, times(1)).notifyAccount(any(), any(), any(), any());
    }

    @Test
    void points_at_the_project_and_says_only_the_fixed_sentence() {
        notifier.onRunReady(new AxRenewalRunReadyEvent(projectId, runId, null));

        ArgumentCaptor<NotificationTarget> target = ArgumentCaptor.forClass(NotificationTarget.class);
        verify(notifications)
                .notifyAccount(
                        eq(reviewer),
                        eq(NotificationType.AX_RENEWAL_RUN_READY),
                        target.capture(),
                        // The body renders on a lock screen (ADR-025 decision 6).
                        eq(NotificationType.AX_RENEWAL_RUN_READY.body()));
        assertThat(target.getValue().id()).isEqualTo(projectId.toString());
        assertThat(target.getValue().iconUrl()).isNull();
    }

    @Test
    void one_member_s_failed_delivery_does_not_cost_the_others_theirs() {
        doThrow(new IllegalStateException("push service down"))
                .when(notifications)
                .notifyAccount(eq(starter), any(), any(), any());

        assertThatCode(() -> notifier.onRunReady(new AxRenewalRunReadyEvent(projectId, runId, null)))
                .doesNotThrowAnyException();

        verify(notifications).notifyAccount(eq(reviewer), any(), any(), any());
        verify(notifications).notifyAccount(eq(lead), any(), any(), any());
    }

    @Test
    void a_failure_to_read_the_members_is_logged_never_thrown() {
        when(members.listForProject(projectId)).thenThrow(new IllegalStateException("database down"));

        assertThatCode(() -> notifier.onRunReady(new AxRenewalRunReadyEvent(projectId, runId, null)))
                .doesNotThrowAnyException();

        verifyNoInteractions(notifications);
    }

    @Test
    void runs_after_commit_off_the_request_thread_and_also_without_a_transaction() throws NoSuchMethodException {
        // The runner's move to awaiting_review is one auto-committed update on
        // its own thread, with no transaction to wait for: without the
        // fallback, Spring would drop that event and nobody would be told.
        Method handler = AxRenewalRunReadyNotifier.class.getMethod("onRunReady", AxRenewalRunReadyEvent.class);

        assertThat(handler.getAnnotation(Async.class)).isNotNull();
        TransactionalEventListener listener = handler.getAnnotation(TransactionalEventListener.class);
        assertThat(listener).isNotNull();
        assertThat(listener.phase()).isEqualTo(TransactionPhase.AFTER_COMMIT);
        assertThat(listener.fallbackExecution()).isTrue();
    }
}
