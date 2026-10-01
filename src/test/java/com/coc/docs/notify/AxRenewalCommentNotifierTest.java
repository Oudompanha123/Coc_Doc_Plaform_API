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

import com.coc.docs.axrenewal.AxRenewalCommentAddedEvent;
import com.coc.docs.axrenewal.AxRenewalMember;
import com.coc.docs.axrenewal.AxRenewalMemberRepository;
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
 * Telling a project's members that somebody commented on it (ADR-080).
 *
 * <p>Delivery itself — opt-outs, the stored bell row, the stream, push — is
 * {@link NotificationService#notifyAccount}'s, covered by {@code
 * NotificationServiceTest}. This covers who is told, what they are told,
 * when, and that a failure stays contained.
 */
class AxRenewalCommentNotifierTest {

    private final NotificationService notifications = mock(NotificationService.class);
    private final AxRenewalMemberRepository members = mock(AxRenewalMemberRepository.class);
    private final AxRenewalCommentNotifier notifier = new AxRenewalCommentNotifier(notifications, members);

    private final UUID projectId = UUID.randomUUID();
    private final UUID commentId = UUID.randomUUID();
    private final UUID author = UUID.randomUUID();
    private final UUID reviewer = UUID.randomUUID();
    private final UUID lead = UUID.randomUUID();

    private AxRenewalMember member(UUID accountId, String name) {
        return new AxRenewalMember(projectId, accountId, name, Instant.EPOCH);
    }

    private AxRenewalCommentAddedEvent comment() {
        return new AxRenewalCommentAddedEvent(projectId, commentId, author);
    }

    @BeforeEach
    void team() {
        when(members.listForProject(projectId))
                .thenReturn(List.of(member(author, "Dara"), member(reviewer, "Sokha"), member(lead, "Vanna")));
    }

    @Test
    void tells_every_member_but_the_author() {
        notifier.onCommentAdded(comment());

        verify(notifications).notifyAccount(eq(reviewer), eq(NotificationType.AX_RENEWAL_COMMENT_ADDED), any(), any());
        verify(notifications).notifyAccount(eq(lead), eq(NotificationType.AX_RENEWAL_COMMENT_ADDED), any(), any());
        verify(notifications, never()).notifyAccount(eq(author), any(), any(), any());
    }

    @Test
    void tells_nobody_when_the_author_is_the_only_member() {
        when(members.listForProject(projectId)).thenReturn(List.of(member(author, "Dara")));

        notifier.onCommentAdded(comment());

        verifyNoInteractions(notifications);
    }

    @Test
    void tells_only_the_project_s_members() {
        // A super admin who commented on a project they are not on is not
        // in the list — and neither is anybody else who merely could open it.
        when(members.listForProject(projectId)).thenReturn(List.of(member(reviewer, "Sokha")));

        notifier.onCommentAdded(comment());

        verify(notifications, times(1)).notifyAccount(any(), any(), any(), any());
        verify(notifications).notifyAccount(eq(reviewer), any(), any(), any());
    }

    @Test
    void points_at_the_project_and_says_only_the_fixed_sentence() {
        notifier.onCommentAdded(comment());

        ArgumentCaptor<NotificationTarget> target = ArgumentCaptor.forClass(NotificationTarget.class);
        verify(notifications)
                .notifyAccount(
                        eq(reviewer),
                        eq(NotificationType.AX_RENEWAL_COMMENT_ADDED),
                        target.capture(),
                        // Neither the comment's text nor the project's name
                        // goes on a lock screen (ADR-025 decision 6).
                        eq(NotificationType.AX_RENEWAL_COMMENT_ADDED.body()));
        assertThat(target.getValue().id()).isEqualTo(projectId.toString());
        assertThat(target.getValue().iconUrl()).isNull();
    }

    @Test
    void one_member_s_failed_delivery_does_not_cost_the_others_theirs() {
        doThrow(new IllegalStateException("push service down"))
                .when(notifications)
                .notifyAccount(eq(reviewer), any(), any(), any());

        assertThatCode(() -> notifier.onCommentAdded(comment())).doesNotThrowAnyException();

        verify(notifications).notifyAccount(eq(lead), any(), any(), any());
    }

    @Test
    void a_failure_to_read_the_members_is_logged_never_thrown() {
        when(members.listForProject(projectId)).thenThrow(new IllegalStateException("database down"));

        assertThatCode(() -> notifier.onCommentAdded(comment())).doesNotThrowAnyException();

        verifyNoInteractions(notifications);
    }

    @Test
    void runs_after_commit_and_off_the_request_thread() throws NoSuchMethodException {
        // A comment that rolled back must tell nobody; a slow push service
        // must not sit inside the request of whoever pressed Send.
        Method handler = AxRenewalCommentNotifier.class.getMethod("onCommentAdded", AxRenewalCommentAddedEvent.class);

        assertThat(handler.getAnnotation(Async.class)).isNotNull();
        TransactionalEventListener listener = handler.getAnnotation(TransactionalEventListener.class);
        assertThat(listener).isNotNull();
        assertThat(listener.phase()).isEqualTo(TransactionPhase.AFTER_COMMIT);
    }
}
