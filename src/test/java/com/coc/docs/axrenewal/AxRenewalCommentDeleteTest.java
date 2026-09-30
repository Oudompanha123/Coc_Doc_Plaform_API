package com.coc.docs.axrenewal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.coc.docs.account.AppRole;
import com.coc.docs.audit.AuditAction;
import com.coc.docs.audit.AuditRepository;
import com.coc.docs.auth.CurrentUser;
import com.coc.docs.auth.TestCallers;
import com.coc.docs.web.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Who may remove a comment (ADR-045).
 *
 * <p>This is the one authorisation rule in {@code AxRenewalService} that
 * {@code SecurityConfig} cannot make, because "is this yours" is row data —
 * so it is the one worth testing here rather than in {@code
 * SecurityRulesTest}, which covers the floor the route sits at.
 *
 * <p>// req: ADR-037, ADR-045
 */
class AxRenewalCommentDeleteTest {

    private AxRenewalProjectRepository projects;
    private AxRenewalCommentRepository comments;
    private AuditRepository audit;
    private AxRenewalService service;

    private final UUID projectId = UUID.randomUUID();
    private final UUID commentId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        projects = mock(AxRenewalProjectRepository.class);
        comments = mock(AxRenewalCommentRepository.class);
        audit = mock(AuditRepository.class);

        when(projects.findById(projectId)).thenReturn(Optional.of(new AxRenewalProject(
                projectId,
                "Legacy Portal",
                null,
                AxRenewalProject.STATUS_DRAFT,
                null,
                null,
                null,
                null,
                null,
                null,
                Instant.EPOCH,
                Instant.EPOCH)));

        service = new AxRenewalService(
                projects,
                mock(AxRenewalAnalysisRepository.class),
                mock(AxRenewalPlanRepository.class),
                mock(AxRenewalDatabaseConnectionRepository.class),
                mock(AxRenewalDestinationRepository.class),
                mock(AxRenewalConfirmationRepository.class),
                mock(AxRenewalImplementationRepository.class),
                comments,
                audit,
                new ObjectMapper(),
                mock(LocalClaudeAnalyzer.class),
                mock(LocalClaudeChat.class),
                new AxRenewalProperties(null, null),
                mock(AxRenewalCodeGenerator.class),
                mock(AxRenewalImplementationRunner.class),
                mock(AxRenewalLocalRunner.class),
                mock(AxRenewalTestRunner.class));
    }

    private void commentExistsBy(UUID authorId) {
        when(comments.findLive(projectId, commentId))
                .thenReturn(Optional.of(new AxRenewalComment(
                        commentId, projectId, authorId, "Author", "a remark", Instant.EPOCH)));
    }

    @Test
    void the_author_may_remove_their_own() {
        CurrentUser author = TestCallers.user(AppRole.USER);
        commentExistsBy(author.id());
        when(comments.softDelete(commentId, author.id())).thenReturn(true);

        service.deleteComment(projectId, commentId, author);

        verify(comments).softDelete(commentId, author.id());
    }

    @Test
    void somebody_else_may_not_even_as_an_admin() {
        // Rank is not ownership. An Admin out-ranks the author but does not
        // get to remove what they said — only a super_admin does.
        commentExistsBy(UUID.randomUUID());

        assertThatThrownBy(() -> service.deleteComment(projectId, commentId, TestCallers.user(AppRole.ADMIN)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("only its author or a super admin");

        verify(comments, never()).softDelete(any(), any());
    }

    @Test
    void a_super_admin_may_remove_anybody_s() {
        CurrentUser superAdmin = TestCallers.user(AppRole.SUPER_ADMIN);
        commentExistsBy(UUID.randomUUID());
        when(comments.softDelete(eq(commentId), any())).thenReturn(true);

        service.deleteComment(projectId, commentId, superAdmin);

        verify(comments).softDelete(commentId, superAdmin.id());
    }

    @Test
    void an_already_removed_comment_is_simply_not_there() {
        // Not "gone" as a separate state the caller has to understand: it is
        // not in the thread, which is all anybody can observe.
        when(comments.findLive(projectId, commentId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteComment(projectId, commentId, TestCallers.user(AppRole.SUPER_ADMIN)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("comment");

        verify(audit, never()).append(any(), any(), any(), any(), any(), any());
    }

    @Test
    void a_lost_race_records_nothing() {
        // Two people pressing Remove at the same moment: one update wins, and
        // the loser must not write a second audit entry for one removal.
        CurrentUser author = TestCallers.user(AppRole.USER);
        commentExistsBy(author.id());
        when(comments.softDelete(commentId, author.id())).thenReturn(false);

        assertThatThrownBy(() -> service.deleteComment(projectId, commentId, author))
                .isInstanceOf(ApiException.class);

        verify(audit, never()).append(any(), any(), any(), any(), any(), any());
    }

    @Test
    void the_audit_entry_says_whether_it_was_their_own() {
        CurrentUser superAdmin = TestCallers.user(AppRole.SUPER_ADMIN);
        commentExistsBy(UUID.randomUUID());
        when(comments.softDelete(eq(commentId), any())).thenReturn(true);

        service.deleteComment(projectId, commentId, superAdmin);

        ArgumentCaptor<JsonNode> detail = ArgumentCaptor.forClass(JsonNode.class);
        verify(audit)
                .append(
                        eq(superAdmin.id()),
                        eq(superAdmin.email()),
                        eq(AuditAction.AX_RENEWAL_COMMENT_DELETE),
                        eq(AuditAction.TARGET_AX_RENEWAL_PROJECT),
                        eq(projectId),
                        detail.capture());

        // Somebody reading this log wants to know a super admin removed
        // another person's remark, not merely that a delete happened.
        assertThat(detail.getValue().path("ownComment").asBoolean()).isFalse();
        assertThat(detail.getValue().path("commentId").asText()).isEqualTo(commentId.toString());
    }
}
