package com.coc.docs.axrenewal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.coc.docs.account.Account;
import com.coc.docs.account.AccountRepository;
import com.coc.docs.account.AccountStatus;
import com.coc.docs.account.AppRole;
import com.coc.docs.audit.AuditAction;
import com.coc.docs.audit.AuditRepository;
import com.coc.docs.auth.CurrentUser;
import com.coc.docs.auth.TestCallers;
import com.coc.docs.web.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpStatus;

/**
 * Project members (ADR-069, revised by ADR-070): only a project's creator,
 * the people explicitly added to it, and a super admin can see it at all.
 * What a member may then do is still their role's question.
 *
 * <p>Tested at the service because "is this caller on this project" is row
 * data — {@code SecurityRulesTest} covers only the role floors the routes
 * sit at.
 *
 * <p>// req: ADR-069, ADR-070
 */
class AxRenewalMembershipTest {

    private AxRenewalProjectRepository projects;
    private AxRenewalPlanRepository plan;
    private AxRenewalConfirmationRepository confirmations;
    private AxRenewalImplementationRepository implementations;
    private AxRenewalCommentRepository comments;
    private AxRenewalMemberRepository members;
    private AccountRepository accounts;
    private AuditRepository audit;
    /** Every event the service published — the member-added (ADR-075) and comment (ADR-080) notifications' triggers. */
    private final List<Object> published = new ArrayList<>();
    private AxRenewalService service;

    private final UUID projectId = UUID.randomUUID();
    private final UUID creatorId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        projects = mock(AxRenewalProjectRepository.class);
        plan = mock(AxRenewalPlanRepository.class);
        confirmations = mock(AxRenewalConfirmationRepository.class);
        implementations = mock(AxRenewalImplementationRepository.class);
        comments = mock(AxRenewalCommentRepository.class);
        // Nobody is a member unless a test says so.
        members = mock(AxRenewalMemberRepository.class);
        accounts = mock(AccountRepository.class);
        audit = mock(AuditRepository.class);

        when(projects.findById(projectId)).thenReturn(Optional.of(project()));
        when(projects.lockRevision(projectId)).thenReturn(Optional.of(0));

        service = new AxRenewalService(
                projects,
                mock(AxRenewalAnalysisRepository.class),
                plan,
                mock(AxRenewalDatabaseConnectionRepository.class),
                mock(AxRenewalDestinationRepository.class),
                confirmations,
                implementations,
                comments,
                members,
                accounts,
                audit,
                new ObjectMapper(),
                mock(LocalClaudeAnalyzer.class),
                mock(LocalClaudeChat.class),
                new AxRenewalProperties(null, null),
                mock(AxRenewalCodeGenerator.class),
                mock(AxRenewalImplementationRunner.class),
                mock(AxRenewalLocalRunner.class),
                mock(AxRenewalTestRunner.class),
                published::add);
    }

    @FunctionalInterface
    interface ProjectChange {
        void apply(AxRenewalService service, UUID projectId, CurrentUser actor);
    }

    private static Arguments change(String name, ProjectChange call) {
        return Arguments.of(name, call);
    }

    /**
     * Every service method that reads or changes one project — each must
     * refuse a non-member before anything else happens (ADR-070). Reads are
     * listed first: they are what ADR-069 used to leave open.
     */
    static Stream<Arguments> everyProjectAccess() {
        UUID runId = UUID.randomUUID();
        return Stream.of(
                change("read project detail", (s, p, a) -> s.detail(p, a)),
                change("read generated changes", (s, p, a) -> s.findGeneratedChanges(p, runId, a)),
                change("read run history", (s, p, a) -> s.listRuns(p, a)),
                change("read generation progress", (s, p, a) -> s.implementationProgress(p, runId, a)),
                change("read run locally status", (s, p, a) -> s.runLocallyStatus(p, a)),
                change("explain a run locally failure", (s, p, a) -> s.explainRunLocallyFailure(p, a)),
                change("chat about the project", (s, p, a) -> s.chat(p, List.of(), "what runs this?", null, a)),
                change("comment", (s, p, a) -> s.addComment(p, "hello", a)),
                change("remove a comment", (s, p, a) -> s.deleteComment(p, UUID.randomUUID(), a)),
                change("delete project", (s, p, a) -> s.delete(p, a)),
                change("rename project", (s, p, a) -> s.updateDetails(p, "Renamed", "new description", a)),
                change("attach manifest", (s, p, a) -> s.attachManifest(p, null, null, null, a)),
                change("run analysis", (s, p, a) -> s.runAnalysis(p, false, null, a)),
                change("edit stack move", (s, p, a) -> s.updateStackMove(p, List.of(), 0, a)),
                change("edit architecture", (s, p, a) -> s.updateArchitecture(p, "x", null, 0, a)),
                change("edit pages", (s, p, a) -> s.updatePages(p, List.of(), 0, a)),
                change("edit database plan", (s, p, a) -> s.updateDatabasePlan(p, List.of(), 0, a)),
                change("connect database", (s, p, a) -> s.connectDatabase(p, "postgres", null, null, null, null, 0, a)),
                change("set destination", (s, p, a) -> s.setDestination(p, "git_repo", "repo", null, null, 0, a)),
                change("confirm section", (s, p, a) -> s.confirmSection(p, AxRenewalSection.PAGES, 0, a)),
                change("reopen section", (s, p, a) -> s.reopenSection(p, AxRenewalSection.PAGES, a)),
                change("start implementation", (s, p, a) -> s.startImplementation(p, null, a)),
                change("start error fix", (s, p, a) -> s.startErrorFixImplementation(p, a)),
                change("start UI change", (s, p, a) -> s.startUiChangeImplementation(p, "make it blue", a)),
                change("approve run", (s, p, a) -> s.approveImplementation(p, runId, a)),
                change("reject run", (s, p, a) -> s.rejectImplementation(p, runId, a)),
                change("cancel run", (s, p, a) -> s.cancelImplementation(p, runId, a)),
                change("run tests", (s, p, a) -> s.runTests(p, runId, a)),
                change("revert run", (s, p, a) -> s.revertImplementation(p, runId, a)),
                change("start run locally", (s, p, a) -> s.startRunLocally(p, a)),
                change("stop run locally", (s, p, a) -> s.stopRunLocally(p, a)),
                change("add member", (s, p, a) -> s.addMember(p, "someone@example.com", a)),
                change("remove member", (s, p, a) -> s.removeMember(p, UUID.randomUUID(), a)));
    }

    @Nested
    class WhoMayAccessAProject {

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.coc.docs.axrenewal.AxRenewalMembershipTest#everyProjectAccess")
        void a_non_member_gets_a_404_and_nothing_is_read_or_written(String name, ProjectChange call) {
            // An Admin who also holds Developer passes every role floor there
            // is — so the only thing refusing them here is membership.
            CurrentUser outsider = TestCallers.user(Set.of(AppRole.ADMIN, AppRole.DEVELOPER));

            assertThatThrownBy(() -> call.apply(service, projectId, outsider))
                    .isInstanceOfSatisfying(ApiException.class, e -> {
                        assertThat(e.status()).isEqualTo(HttpStatus.NOT_FOUND);
                        assertThat(e.getMessage()).isEqualTo("project not found");
                    });

            verify(projects, never()).lockRevision(any());
            verify(projects, never()).delete(any());
            verify(members, never()).add(any(), any(), any());
            verify(members, never()).remove(any(), any());
            verify(members, never()).listForProject(any());
            verifyNoInteractions(plan, confirmations, implementations, comments, accounts, audit);
            // Nor is anybody notified of anything (ADR-075, ADR-080).
            assertThat(published).isEmpty();
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.coc.docs.axrenewal.AxRenewalMembershipTest#everyProjectAccess")
        void a_non_member_cannot_tell_the_project_exists(String name, ProjectChange call) {
            // A 403 would confirm the id belongs to a real project. The
            // refusal for "not invited" must be indistinguishable from the
            // one for "never existed" — same status, same message.
            CurrentUser outsider = TestCallers.user(AppRole.ADMIN);
            ApiException notInvited = catchApiException(() -> call.apply(service, projectId, outsider));

            when(projects.findById(projectId)).thenReturn(Optional.empty());
            ApiException missing = catchApiException(() -> call.apply(service, projectId, outsider));

            assertThat(notInvited.status()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(missing.status()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(notInvited.getMessage()).isEqualTo(missing.getMessage());
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.coc.docs.axrenewal.AxRenewalMembershipTest#everyProjectAccess")
        void a_missing_project_is_a_404(String name, ProjectChange call) {
            when(projects.findById(projectId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> call.apply(service, projectId, TestCallers.user(AppRole.ADMIN)))
                    .isInstanceOfSatisfying(
                            ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.NOT_FOUND));
        }

        @Test
        void a_member_can_read_the_project_detail() {
            CurrentUser reviewer = TestCallers.user(Set.of(AppRole.REVIEWER));
            when(members.isMember(projectId, reviewer.id())).thenReturn(true);

            assertThat(service.detail(projectId, reviewer).project().id()).isEqualTo(projectId);
        }

        @Test
        void a_super_admin_can_read_a_project_they_are_not_on() {
            // Super Admin follows the existing policy: the same any-project
            // override they already had over changes (ADR-045, ADR-069).
            assertThat(service.detail(projectId, TestCallers.user(AppRole.SUPER_ADMIN)).project().id())
                    .isEqualTo(projectId);
            verify(members, never()).isMember(any(), any());
        }

        @Test
        void a_member_gets_past_the_check() {
            CurrentUser developer = TestCallers.user(Set.of(AppRole.DEVELOPER));
            when(members.isMember(projectId, developer.id())).thenReturn(true);

            service.reopenSection(projectId, AxRenewalSection.PAGES, developer);

            verify(confirmations).clear(projectId, AxRenewalSection.PAGES);
        }

        @Test
        void a_super_admin_may_change_a_project_they_are_not_on() {
            // The same override a super admin has over anyone's comment
            // (ADR-045) — and the only way back into a project whose team
            // has all left the organisation.
            service.reopenSection(projectId, AxRenewalSection.PAGES, TestCallers.user(AppRole.SUPER_ADMIN));

            verify(confirmations).clear(projectId, AxRenewalSection.PAGES);
            verify(members, never()).isMember(any(), any());
        }

        @Test
        void a_member_may_comment() {
            CurrentUser reviewer = TestCallers.user(Set.of(AppRole.REVIEWER));
            when(members.isMember(projectId, reviewer.id())).thenReturn(true);
            UUID commentId = UUID.randomUUID();
            when(comments.insert(eq(projectId), eq(reviewer.id()), any(), anyString()))
                    .thenReturn(new AxRenewalComment(
                            commentId, projectId, reviewer.id(), "Reviewer", "looks right", Instant.EPOCH));

            service.addComment(projectId, "looks right", reviewer);

            verify(comments).insert(eq(projectId), eq(reviewer.id()), any(), eq("looks right"));
            // The team is told — ids only; the notifier leaves out the author (ADR-080).
            assertThat(published).containsExactly(new AxRenewalCommentAddedEvent(projectId, commentId, reviewer.id()));
        }

        @Test
        void a_blank_comment_is_refused_and_notifies_nobody() {
            CurrentUser reviewer = TestCallers.user(Set.of(AppRole.REVIEWER));
            when(members.isMember(projectId, reviewer.id())).thenReturn(true);

            assertThatThrownBy(() -> service.addComment(projectId, "   ", reviewer))
                    .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST));

            verify(comments, never()).insert(any(), any(), any(), any());
            assertThat(published).isEmpty();
        }

        @Test
        void creating_a_project_makes_its_creator_the_first_member() {
            CurrentUser admin = TestCallers.user(AppRole.ADMIN);
            when(projects.insert(any(), any(), eq(admin.id()))).thenReturn(project());

            service.create("Legacy Portal", null, admin);

            verify(members).add(projectId, admin.id(), admin.id());
        }
    }

    @Nested
    class TheProjectList {

        @Test
        void lists_only_the_callers_own_projects() {
            // Scoped in the query (listForMember), never "load everything and
            // filter" — a project the caller is not on is not loaded at all.
            CurrentUser admin = TestCallers.user(AppRole.ADMIN);
            when(projects.listForMember(admin.id())).thenReturn(List.of(project()));

            assertThat(service.list(admin)).extracting(AxRenewalProject::id).containsExactly(projectId);
            verify(projects, never()).list();
        }

        @Test
        void lists_every_project_for_a_super_admin() {
            when(projects.list()).thenReturn(List.of(project()));

            assertThat(service.list(TestCallers.user(AppRole.SUPER_ADMIN))).hasSize(1);
            verify(projects, never()).listForMember(any());
        }
    }

    @Nested
    class AddingAMember {

        private final CurrentUser admin = TestCallers.user(AppRole.ADMIN);

        @BeforeEach
        void callerIsOnTheProject() {
            when(members.isMember(projectId, admin.id())).thenReturn(true);
        }

        private Account accountWith(AccountStatus status, AppRole... roles) {
            return TestCallers.user(Set.of(roles)).account().withStatus(status);
        }

        @Test
        void adds_an_active_account_that_can_open_the_workspace_and_audits_it() {
            Account reviewer = accountWith(AccountStatus.ACTIVE, AppRole.REVIEWER);
            when(accounts.findByEmail("reviewer@example.com")).thenReturn(Optional.of(reviewer));
            when(members.add(projectId, reviewer.id(), admin.id())).thenReturn(true);

            service.addMember(projectId, "  reviewer@example.com ", admin);

            verify(members).add(projectId, reviewer.id(), admin.id());
            // ADR-075: the person added is told — by an event, handled after commit.
            assertThat(published).containsExactly(new AxRenewalMemberAddedEvent(projectId, reviewer.id()));
            verify(audit).append(
                    eq(admin.id()),
                    eq(admin.email()),
                    eq(AuditAction.AX_RENEWAL_MEMBER_ADD),
                    eq(AuditAction.TARGET_AX_RENEWAL_PROJECT),
                    eq(projectId),
                    any());
        }

        @Test
        void adding_someone_already_on_the_project_is_not_an_error_and_not_audited_twice() {
            Account developer = accountWith(AccountStatus.ACTIVE, AppRole.DEVELOPER);
            when(accounts.findByEmail(any())).thenReturn(Optional.of(developer));
            when(members.add(projectId, developer.id(), admin.id())).thenReturn(false);

            service.addMember(projectId, "dev@example.com", admin);

            verifyNoInteractions(audit);
            // Already a member: nothing new happened to them, so nothing to tell.
            assertThat(published).isEmpty();
        }

        @Test
        void adding_yourself_notifies_nobody() {
            // A super admin may add themselves to any project (ADR-070); they
            // know already, so a notification would only be noise.
            CurrentUser superAdmin = TestCallers.user(AppRole.SUPER_ADMIN);
            when(accounts.findByEmail(any())).thenReturn(Optional.of(superAdmin.account()));
            when(members.add(projectId, superAdmin.id(), superAdmin.id())).thenReturn(true);

            service.addMember(projectId, "me@example.com", superAdmin);

            verify(members).add(projectId, superAdmin.id(), superAdmin.id());
            assertThat(published).isEmpty();
        }

        @Test
        void a_refused_add_notifies_nobody() {
            when(accounts.findByEmail(any())).thenReturn(Optional.of(accountWith(AccountStatus.ACTIVE, AppRole.USER)));

            assertThatThrownBy(() -> service.addMember(projectId, "user@example.com", admin))
                    .isInstanceOf(ApiException.class);
            assertThat(published).isEmpty();
        }

        @Test
        void an_unknown_email_is_a_404() {
            when(accounts.findByEmail(any())).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.addMember(projectId, "nobody@example.com", admin))
                    .isInstanceOfSatisfying(
                            ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.NOT_FOUND));
            verify(members, never()).add(any(), any(), any());
        }

        @Test
        void an_account_that_cannot_open_ax_renewal_is_refused() {
            // A plain user could never reach the project they were added to.
            when(accounts.findByEmail(any())).thenReturn(Optional.of(accountWith(AccountStatus.ACTIVE, AppRole.USER)));

            assertThatThrownBy(() -> service.addMember(projectId, "user@example.com", admin))
                    .isInstanceOfSatisfying(ApiException.class, e -> {
                        assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                        assertThat(e.getMessage()).contains("cannot open AX Renewal");
                    });
            verify(members, never()).add(any(), any(), any());
        }

        @Test
        void an_account_that_is_not_active_is_refused() {
            when(accounts.findByEmail(any()))
                    .thenReturn(Optional.of(accountWith(AccountStatus.PENDING, AppRole.DEVELOPER)));

            assertThatThrownBy(() -> service.addMember(projectId, "pending@example.com", admin))
                    .isInstanceOfSatisfying(
                            ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST));
            verify(members, never()).add(any(), any(), any());
        }
    }

    @Nested
    class RemovingAMember {

        private final CurrentUser admin = TestCallers.user(AppRole.ADMIN);
        private final UUID teammate = UUID.randomUUID();

        @BeforeEach
        void callerAndTeammateAreOnTheProject() {
            when(members.isMember(projectId, admin.id())).thenReturn(true);
            when(members.isMember(projectId, teammate)).thenReturn(true);
        }

        @Test
        void removes_a_member_and_audits_it() {
            when(members.count(projectId)).thenReturn(2);

            service.removeMember(projectId, teammate, admin);

            verify(members).remove(projectId, teammate);
            verify(audit).append(
                    eq(admin.id()),
                    eq(admin.email()),
                    eq(AuditAction.AX_RENEWAL_MEMBER_REMOVE),
                    eq(AuditAction.TARGET_AX_RENEWAL_PROJECT),
                    eq(projectId),
                    any());
        }

        @Test
        void locks_the_project_row_first_so_two_removals_cannot_both_leave_none() {
            when(members.count(projectId)).thenReturn(2);

            service.removeMember(projectId, teammate, admin);

            verify(projects).lockRevision(projectId);
        }

        @Test
        void the_last_member_cannot_be_removed() {
            // A project nobody is on could only be changed by a super admin.
            when(members.count(projectId)).thenReturn(1);

            assertThatThrownBy(() -> service.removeMember(projectId, admin.id(), admin))
                    .isInstanceOfSatisfying(ApiException.class, e -> {
                        assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                        assertThat(e.getMessage()).contains("at least one member");
                    });
            verify(members, never()).remove(any(), any());
            verifyNoInteractions(audit);
        }

        @Test
        void the_creator_cannot_be_removed() {
            // "The creator and the people they invited": the creator is not
            // one of the removable ones (ADR-070), however many others remain.
            when(members.isMember(projectId, creatorId)).thenReturn(true);
            when(members.count(projectId)).thenReturn(3);

            assertThatThrownBy(() -> service.removeMember(projectId, creatorId, admin))
                    .isInstanceOfSatisfying(ApiException.class, e -> {
                        assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                        assertThat(e.getMessage()).contains("creator");
                    });
            verify(members, never()).remove(any(), any());
        }

        @Test
        void removing_someone_who_is_not_a_member_is_a_404() {
            UUID stranger = UUID.randomUUID();

            assertThatThrownBy(() -> service.removeMember(projectId, stranger, admin))
                    .isInstanceOfSatisfying(
                            ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.NOT_FOUND));
            verify(members, never()).remove(any(), any());
        }
    }

    private static ApiException catchApiException(Runnable call) {
        try {
            call.run();
        } catch (ApiException e) {
            return e;
        }
        throw new AssertionError("expected an ApiException");
    }

    private AxRenewalProject project() {
        return new AxRenewalProject(
                projectId,
                "Legacy Portal",
                null,
                AxRenewalProject.STATUS_PLANNING,
                null,
                null,
                null,
                null,
                null,
                creatorId,
                Instant.EPOCH,
                Instant.EPOCH,
                0);
    }
}
