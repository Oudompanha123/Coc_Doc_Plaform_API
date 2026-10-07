package com.coc.docs.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.coc.docs.account.AccountService;
import com.coc.docs.account.AppRole;
import com.coc.docs.auth.SessionAuthenticationFilter;
import com.coc.docs.auth.SessionService;
import com.coc.docs.auth.TestCallers;
import com.coc.docs.config.AppProperties;
import com.coc.docs.config.ProblemResponses;
import com.coc.docs.config.SecurityConfig;
import com.coc.docs.content.BlockBodyValidator;
import com.coc.docs.docscontent.DocsContentService;
import com.coc.docs.nav.NavProduct;
import com.coc.docs.nav.NavService;
import com.coc.docs.page.PageService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Tests the authorisation rules in {@link SecurityConfig} directly.
 *
 * <p>These run with no database and no Docker, which matters: they are the
 * checks that must never be skipped, and a test that only runs where Docker is
 * available would be skipped exactly on the machine where someone is adding a
 * route.
 *
 * <p>ADR-011 rejected per-route checks (alternative B2) because a forgotten
 * check on one new route is a silent hole. The last test here is the one that
 * makes that choice real: an undeclared route is reachable only by a super
 * admin, so the failure mode of forgetfulness is a locked door.
 *
 * <p>// req: FR-AUTH-005, FR-AUTH-006, FR-AUTH-008, NFR-SEC-AUTH, ADR-011, TM-005,
 * TM-018, TM-019
 */
@WebMvcTest
@Import({SecurityConfig.class, ProblemResponses.class, SecurityRulesTest.Beans.class, PublicIngestUrl.class})
class SecurityRulesTest {

    @Autowired MockMvc mvc;

    // Stubbed because these tests are about who may reach a route, not what it
    // returns. A rule that admitted the wrong caller would be caught here even
    // if every service threw.
    @MockitoBean PageService pageService;
    @MockitoBean AccountService accountService;
    @MockitoBean SessionService sessionService;
    @MockitoBean BlockBodyValidator validator;

    // DocsContentController/AdminDocsContentController are in this slice, so
    // their dependency has to be too (same reasoning as deviceTokenRepository
    // below) — this test is about which routes the filter chain admits, not
    // about what the docs-content store actually does.
    @MockitoBean DocsContentService docsContentService;

    // NavController/AdminNavController are in this slice too, same reasoning.
    @MockitoBean NavService navService;

    // AdminNotificationController needs it to construct. Mocked for the same
    // reason as the rest: this test is about who may reach the route, not about
    // what the digest does — that is ActivityDigestServiceTest's job.
    @MockitoBean com.coc.docs.notify.ActivityDigestService activityDigestService;

    /** NotificationFeedController's second collaborator, behind GET /notifications/hrms/{id} (ADR-042). */
    @MockitoBean com.coc.docs.notify.HrmsNotificationService hrmsNotificationService;

    // MailCredentialController is in this slice (ADR-063), so its dependency
    // has to be too. Mocked like the rest: this class asserts which routes the
    // filter chain admits, not what storing a credential does.
    @MockitoBean com.coc.docs.notify.AccountMailCredentialRepository accountMailCredentialRepository;

    // MailCredentialController's second collaborator since ADR-085, behind
    // POST /me/mail-credential/google.
    @MockitoBean com.coc.docs.notify.GoogleMailOAuthService googleMailOAuthService;

    // AdminAuditController is in this slice too, same reasoning.
    @MockitoBean com.coc.docs.audit.AuditRepository auditRepository;

    // AdminTaskController is in this slice too, same reasoning (ADR-033).
    @MockitoBean com.coc.docs.tasks.TaskService taskService;

    // AuthController needs it to construct (ADR-018); mocked for the same
    // reason as sessionService above — this test is about which routes the
    // filter chain admits, not what password reset actually does.
    @MockitoBean com.coc.docs.auth.PasswordResetService passwordResetService;

    // Mocked: this test is about which routes the filter chain admits, not
    // about what a nonce contains. AuthController needs it to construct, so
    // without it the whole context fails before a single rule is exercised.
    @MockitoBean com.coc.docs.auth.NonceService nonceService;
    @MockitoBean com.coc.docs.auth.SessionRepository sessionRepository;
    @MockitoBean com.coc.docs.account.RoleRepository roleRepository;

    // AuthController needs it to construct (ADR-021); mocked for the same
    // reason as passwordResetService above.
    @MockitoBean com.coc.docs.auth.RegistrationOtpService registrationOtpService;

    // AuthController needs it to construct (ADR-031); mocked for the same
    // reason as registrationOtpService above.
    @MockitoBean com.coc.docs.auth.TotpService totpService;

    // DeviceTokenController is in this slice, so its dependency has to be too
    // (ADR-025). Mocked rather than real: this test is about which routes the
    // filter chain admits, not about what registering a device does.
    @MockitoBean com.coc.docs.notify.DeviceTokenRepository deviceTokenRepository;

    // Same reason as above: NotificationFeedController is in this slice. What
    // the feed contains is NotificationFeedServiceTest's job; what this test
    // cares about is that GET /api/v1/notifications is admitted at the `user`
    // floor while POST /api/v1/admin/notifications/digest stays super_admin.
    @MockitoBean com.coc.docs.notify.NotificationFeedService notificationFeedService;

    // Same reasoning: this class asserts who may reach the inbox routes, not
    // what they return. NotificationPersistenceTest owns the rows.
    @MockitoBean com.coc.docs.notify.NotificationInboxService notificationInboxService;

    // NotificationPreferenceController is in this slice too (ADR-042). This
    // class asserts which routes the filter chain admits, not what a
    // preference does — NotificationPreferenceRepositoryIntegrationTest and
    // NotificationServiceTest own that.
    @MockitoBean com.coc.docs.notify.NotificationPreferenceService notificationPreferenceService;

    // AdminClaudeUsageController and ClaudeUsageIngestController are in this
    // slice (ADR-035), so their dependency has to be too, same reasoning as
    // the rest of this class: this test is about which routes the filter
    // chain admits, not about roster management or ingestion themselves —
    // that is ClaudeUsageServiceTest's job.
    @MockitoBean com.coc.docs.claudeusage.ClaudeUsageService claudeUsageService;

    // ClaudeUsageIngestController and the new ClaudeUsageIngestTokenController
    // (ADR-047) both need it to construct. What it resolves is
    // ClaudeUsageIngestTokenServiceTest's job; this class only asserts who may
    // reach POST /api/ingest and /api/v1/me/usage-tokens/**.
    @MockitoBean com.coc.docs.claudeusage.ClaudeUsageIngestTokenService claudeUsageIngestTokenService;

    // ClaudeUsageCliAuthController is in this slice too (ADR-060) — mocked for
    // the same reason: which routes the chain admits, not what the handshake does.
    @MockitoBean com.coc.docs.claudeusage.ClaudeUsageCliAuthService claudeUsageCliAuthService;

    // Both Claude Usage controllers take it (ADR-096) — mocked for the same reason.
    @MockitoBean com.coc.docs.claudeusage.ClaudeUsageScanLogService claudeUsageScanLogService;

    @MockitoBean com.coc.docs.axrenewal.AxRenewalService axRenewalService;

    @TestConfiguration
    static class Beans {

        /** Real bcrypt, not a stub: these tests exercise the filter chain, and a
         *  fake encoder would let a password path pass that production refuses. */
        @Bean
        PasswordEncoder passwordEncoder() {
            return new BCryptPasswordEncoder();
        }

        @Bean
        AppProperties appProperties() {
            return new AppProperties(
                    Duration.ofDays(1),
                    "http://localhost:3000",
                    false,
                    "",
                    "test-client-secret",
                    new AppProperties.Storage("", "test", "us-east-1", "", ""));
        }

        /**
         * The real filter is not used here.
         *
         * <p>It resolves a cookie against the database, and these tests need to
         * place a caller of a chosen role into the context without one. The
         * filter has its own coverage in the integration suite; what is under
         * test in this class is the rule table, and substituting the filter is
         * what isolates it.
         */
        @Bean
        SessionAuthenticationFilter sessionAuthenticationFilter(SessionService sessions) {
            return new SessionAuthenticationFilter(sessions);
        }

        /**
         * Real, because it is a map of open connections and nothing else.
         *
         * <p>Present only so that {@code NotificationStreamController} can be
         * built — this class is about which roles reach which paths, and the
         * stream endpoint is one of those paths.
         */
        @Bean
        com.coc.docs.notify.NotificationStream notificationStream() {
            return new com.coc.docs.notify.NotificationStream();
        }

        /**
         * Real, not mocked: it is a plain data holder, same treatment as
         * {@link #appProperties()} above. Unconfigured (null secret) — this
         * class is about which roles/sessions reach a route, not about the
         * ingest secret's own check, which {@code ClaudeUsagePath.ingest_*}
         * below relies on to tell "reached the controller" apart from
         * "blocked by the filter chain".
         */
        @Bean
        com.coc.docs.claudeusage.ClaudeUsageProperties claudeUsageProperties() {
            return new com.coc.docs.claudeusage.ClaudeUsageProperties(null);
        }
    }

    /**
     * Authenticates the request as a caller with the given role, through the same
     * authentication object the real filter produces.
     *
     * <p>See {@link TestCallers} for why this is not {@code @WithMockUser}.
     */
    private static RequestPostProcessor as(AppRole role) {
        return SecurityMockMvcRequestPostProcessors.authentication(
                TestCallers.authentication(role));
    }

    @Nested
    @DisplayName("public routes")
    class Public {

        @ParameterizedTest
        @ValueSource(strings = {"/api/v1/health", "/api/v1/auth/me"})
        void are_reachable_without_a_session(String path) throws Exception {
            // /auth/me answers 200 with authenticated:false rather than 401. It is
            // the question the frontend asks on every load, and an anonymous
            // visitor is the expected answer, not an error.
            mvc.perform(get(path)).andExpect(status().isOk());
        }

        @Test
        void nonce_is_reachable_without_a_session() throws Exception {
            // It has to be: it is the first call of a sign-in, made by someone
            // who is by definition not signed in yet. A 401 here would make
            // Google sign-in impossible to start.
            mvc.perform(post("/api/v1/auth/nonce")).andExpect(status().isOk());
        }

        @Test
        void sign_in_is_reachable_without_a_session() throws Exception {
            // 400 because the body is empty, which is the point: the route was
            // reached rather than refused at the security layer.
            mvc.perform(post("/api/v1/auth/google")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void forgot_password_is_reachable_without_a_session() throws Exception {
            // ADR-018. Has to be: a visitor who forgot their password, by
            // definition, has no session to present.
            mvc.perform(post("/api/v1/auth/forgot-password")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"someone@example.com\"}"))
                    .andExpect(status().isOk());
        }

        @Test
        void reset_password_is_reachable_without_a_session() throws Exception {
            // 400 because the body is empty — the route was reached rather
            // than refused at the security layer, same reasoning as
            // sign_in_is_reachable_without_a_session above.
            mvc.perform(post("/api/v1/auth/reset-password")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void register_resend_is_reachable_without_a_session() throws Exception {
            // ADR-021. Has to be: this runs before any account, and
            // therefore any session, exists. 400 because the body is empty —
            // the route was reached rather than refused at the security layer.
            mvc.perform(post("/api/v1/auth/register/resend")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void register_verify_is_reachable_without_a_session() throws Exception {
            // ADR-021. Same necessity as register/resend above.
            mvc.perform(post("/api/v1/auth/register/verify")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isBadRequest());
        }
    }

    @Nested
    @DisplayName("the reader path")
    class ReaderPath {

        @Test
        void refuses_an_anonymous_caller() throws Exception {
            // FR-PUBLIC-001 (guest reading) is Won't-have in the MVP, so the whole
            // site sits at a `user` floor until phase 2 lowers specific pages
            // deliberately under BR-026.
            mvc.perform(get("/api/v1/sections")).andExpect(status().isUnauthorized());
        }

        @Test
        void admits_a_user() throws Exception {
            mvc.perform(get("/api/v1/sections").with(as(AppRole.USER)))
                    .andExpect(status().isOk());
        }

        @Test
        void refuses_a_guest() throws Exception {
            mvc.perform(get("/api/v1/sections").with(as(AppRole.GUEST)))
                    .andExpect(status().isForbidden());
        }
    }

    @Nested
    @DisplayName("the editorial path")
    class EditorialPath {

        @Test
        void refuses_a_user() throws Exception {
            // TM-005 and TM-018: a reader calling the admin API directly. Hiding
            // the button is not a control; this is.
            mvc.perform(get("/api/v1/admin/translation-status").with(as(AppRole.USER)))
                    .andExpect(status().isForbidden());
        }

        @Test
        void admits_an_admin() throws Exception {
            mvc.perform(get("/api/v1/admin/translation-status").with(as(AppRole.ADMIN)))
                    .andExpect(status().isOk());
        }
    }

    @Nested
    @DisplayName("docs content")
    class DocsContentPath {

        @Test
        void the_read_side_is_reachable_without_a_session() throws Exception {
            // /docs/** and the product pages carry no auth gate on the
            // frontend and are meant to be public — a deliberate exception
            // to the reader path's `user` floor above, not an oversight of
            // it. 404 because nothing is mocked to exist for this slug; the
            // point is that the route was reached rather than refused at
            // the security layer, same reasoning as the Public tests above.
            mvc.perform(get("/api/v1/docs-content/jex3-0/overview"))
                    .andExpect(status().isNotFound());
        }

        @Test
        void the_meta_endpoint_is_also_reachable_without_a_session() throws Exception {
            // Same public matcher (/api/v1/docs-content/**) as the read side
            // above — the "About this document" panel's updatedAt lookup.
            mvc.perform(get("/api/v1/docs-content/jex3-0/overview/meta"))
                    .andExpect(status().isNotFound());
        }

        @Test
        void the_write_side_refuses_a_user() throws Exception {
            mvc.perform(put("/api/v1/admin/docs-content/jex3-0/overview")
                            .with(as(AppRole.USER))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isForbidden());
        }

        @Test
        void the_write_side_admits_an_admin() throws Exception {
            mvc.perform(put("/api/v1/admin/docs-content/jex3-0/overview")
                            .with(as(AppRole.ADMIN))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isOk());
        }
    }

    @Nested
    @DisplayName("content images")
    class ContentImagesPath {

        @Test
        void refuses_a_user() throws Exception {
            mvc.perform(multipart("/api/v1/admin/content-images")
                            .file("file", new byte[0])
                            .with(as(AppRole.USER)))
                    .andExpect(status().isForbidden());
        }

        @Test
        void admits_an_admin() throws Exception {
            // 400, not 403: no ContentImageStorage bean exists in this slice
            // (no object-storage credentials configured for the test), so
            // the request reaches the controller and is refused for that
            // business reason, not blocked at the security layer.
            mvc.perform(multipart("/api/v1/admin/content-images")
                            .file("file", new byte[0])
                            .with(as(AppRole.ADMIN)))
                    .andExpect(status().isBadRequest());
        }
    }

    @Nested
    @DisplayName("nav structure")
    class NavStructurePath {

        @Test
        void the_read_side_is_reachable_without_a_session() throws Exception {
            // Same reasoning as DocsContentPath above: the sidebar/docs pages
            // this drives are public, so this is a deliberate exception to
            // the reader path's `user` floor.
            mvc.perform(get("/api/v1/nav")).andExpect(status().isOk());
        }

        @Test
        void the_write_side_refuses_a_user() throws Exception {
            mvc.perform(post("/api/v1/admin/nav/products")
                            .with(as(AppRole.USER))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"key\":\"x\",\"label\":\"X\",\"icon\":\"layers\"}"))
                    .andExpect(status().isForbidden());
        }

        @Test
        void the_write_side_admits_an_admin() throws Exception {
            // Stubbed to return a real record, not Mockito's null default —
            // the controller maps the result through ApiDtos.NavProductResponse.of,
            // which would NPE on a null product before the response status
            // (what this test actually checks) is ever decided.
            Mockito.when(navService.createProduct(
                            ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any()))
                    .thenReturn(new NavProduct("x", "X", "layers", 1, "user", List.of()));

            mvc.perform(post("/api/v1/admin/nav/products")
                            .with(as(AppRole.ADMIN))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"key\":\"x\",\"label\":\"X\",\"icon\":\"layers\"}"))
                    .andExpect(status().isOk());
        }
    }

    @Nested
    @DisplayName("account management")
    class AccountManagement {

        @Test
        void refuses_an_admin() throws Exception {
            // FR-AUTH-008 in one assertion: an admin publishes content, and
            // granting roles is a different kind of authority. One of the few
            // routes inside /admin/** that an admin cannot reach — see also
            // /admin/roles/** and /admin/notifications/**.
            mvc.perform(get("/api/v1/admin/accounts").with(as(AppRole.ADMIN)))
                    .andExpect(status().isForbidden());
        }

        @Test
        void admits_a_super_admin() throws Exception {
            org.mockito.Mockito.when(accountService.list()).thenReturn(List.of());

            mvc.perform(get("/api/v1/admin/accounts").with(as(AppRole.SUPER_ADMIN)))
                    .andExpect(status().isOk());
        }

        @Test
        void refuses_an_admin_attempting_a_role_change() throws Exception {
            mvc.perform(patch("/api/v1/admin/accounts/" + UUID.randomUUID())
                            .with(as(AppRole.ADMIN))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"role\":\"super_admin\"}"))
                    .andExpect(status().isForbidden());
        }
    }

    @Nested
    @DisplayName("notifications")
    class Notifications {

        @Test
        void refuses_an_admin() throws Exception {
            // Above the admin floor deliberately: the mail this sends carries
            // page titles and the names of the people who edited them, and it
            // puts mail in other people's inboxes. Neither is editorial work.
            // // req: FR-AUTH-008, NFR-SEC-PII
            mvc.perform(post("/api/v1/admin/notifications/digest").with(as(AppRole.ADMIN)))
                    .andExpect(status().isForbidden());
        }

        @Test
        void refuses_a_plain_user() throws Exception {
            mvc.perform(post("/api/v1/admin/notifications/digest").with(as(AppRole.USER)))
                    .andExpect(status().isForbidden());
        }

        @Test
        void refuses_a_caller_with_no_session() throws Exception {
            mvc.perform(post("/api/v1/admin/notifications/digest")).andExpect(status().isUnauthorized());
        }

        @Test
        void admits_a_super_admin() throws Exception {
            java.time.LocalDate day = java.time.LocalDate.of(2026, 9, 14);
            org.mockito.Mockito.when(activityDigestService.yesterday()).thenReturn(day);
            org.mockito.Mockito.when(activityDigestService.sendDigestFor(
                            org.mockito.ArgumentMatchers.eq(day),
                            org.mockito.ArgumentMatchers.any()))
                    .thenReturn(new com.coc.docs.notify.ActivityDigestService.DigestResult(
                            day, com.coc.docs.notify.ActivityDigestService.Outcome.SENT, 3, 2, 4));

            mvc.perform(post("/api/v1/admin/notifications/digest").with(as(AppRole.SUPER_ADMIN)))
                    .andExpect(status().isOk());
        }
    }

    @Nested
    @DisplayName("audit trail")
    class AuditTrail {

        @Test
        void refuses_an_admin() throws Exception {
            // Same reasoning as AccountManagement above: every row names
            // another account's email (NFR-SEC-PII), so this is the same
            // class of exposure as /api/v1/admin/accounts/**, not an
            // ordinary editorial read an admin can reach.
            mvc.perform(get("/api/v1/admin/audit/recent").with(as(AppRole.ADMIN)))
                    .andExpect(status().isForbidden());
        }

        @Test
        void admits_a_super_admin() throws Exception {
            org.mockito.Mockito.when(auditRepository.recent(org.mockito.ArgumentMatchers.anyInt()))
                    .thenReturn(List.of());

            mvc.perform(get("/api/v1/admin/audit/recent").with(as(AppRole.SUPER_ADMIN)))
                    .andExpect(status().isOk());
        }

        @Test
        void the_ax_renewal_project_trail_refuses_an_admin_and_a_developer_role_user() throws Exception {
            // Same PII reasoning as the two tests above — this sits under
            // /api/v1/admin/audit/** on purpose, not under /api/v1/ax-renewal/**,
            // so it must not inherit that surface's lower (developer-role)
            // floor even though the project itself is reachable there.
            UUID projectId = UUID.randomUUID();
            mvc.perform(get("/api/v1/admin/audit/ax-renewal/" + projectId).with(as(AppRole.ADMIN)))
                    .andExpect(status().isForbidden());
            mvc.perform(get("/api/v1/admin/audit/ax-renewal/" + projectId)
                            .with(SecurityMockMvcRequestPostProcessors.authentication(
                                    TestCallers.authentication(java.util.Set.of(AppRole.USER, AppRole.DEVELOPER)))))
                    .andExpect(status().isForbidden());
        }

        @Test
        void the_ax_renewal_project_trail_admits_a_super_admin() throws Exception {
            UUID projectId = UUID.randomUUID();
            org.mockito.Mockito.when(auditRepository.forTarget(
                            org.mockito.ArgumentMatchers.eq(com.coc.docs.audit.AuditAction.TARGET_AX_RENEWAL_PROJECT),
                            org.mockito.ArgumentMatchers.eq(projectId),
                            org.mockito.ArgumentMatchers.anyInt()))
                    .thenReturn(List.of());

            mvc.perform(get("/api/v1/admin/audit/ax-renewal/" + projectId).with(as(AppRole.SUPER_ADMIN)))
                    .andExpect(status().isOk());
        }
    }

    @Nested
    @DisplayName("tasks")
    class TasksPath {

        @Test
        void the_read_side_admits_a_plain_user() throws Exception {
            // Open to any signed-in role: a task's title carries no other
            // account's PII, and the Member landing page's "Your Tasks" list
            // (ADR-033) reads this as a plain `user`.
            org.mockito.Mockito.when(taskService.listOpen(org.mockito.ArgumentMatchers.anyInt()))
                    .thenReturn(List.of());

            mvc.perform(get("/api/v1/admin/tasks").with(as(AppRole.USER)))
                    .andExpect(status().isOk());
        }

        @Test
        void the_write_side_refuses_a_plain_user() throws Exception {
            mvc.perform(post("/api/v1/admin/tasks")
                            .with(as(AppRole.USER))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"title\":\"x\"}"))
                    .andExpect(status().isForbidden());
        }

        @Test
        void admits_an_admin() throws Exception {
            org.mockito.Mockito.when(taskService.listOpen(org.mockito.ArgumentMatchers.anyInt()))
                    .thenReturn(List.of());

            mvc.perform(get("/api/v1/admin/tasks").with(as(AppRole.ADMIN)))
                    .andExpect(status().isOk());
        }
    }

    @Nested
    @DisplayName("Claude Usage (ADR-035)")
    class ClaudeUsagePath {

        /*
         * This nest asserted the dashboard refused a plain user, because the
         * route sat under /api/v1/admin/**. It moved to /api/v1/claude-usage
         * so a user could read their OWN row, and the tests moved with it.
         * What must not move is the write side: the two are asserted together
         * below, because opening a read and opening a write look identical in
         * a diff and are not the same thing at all.
         */

        @Test
        void the_dashboard_admits_a_plain_user() throws Exception {
            org.mockito.Mockito.when(claudeUsageService.dashboardFor(
                            org.mockito.ArgumentMatchers.any(),
                            org.mockito.ArgumentMatchers.any(),
                            org.mockito.ArgumentMatchers.anyInt()))
                    .thenReturn(new com.coc.docs.claudeusage.ClaudeUsageDashboard(
                            "week",
                            java.time.LocalDate.now(),
                            java.time.LocalDate.now(),
                            List.of(),
                            List.of(),
                            new com.coc.docs.claudeusage.ClaudeUsageTotals(0, 0, 0, 0, java.math.BigDecimal.ZERO),
                            0,
                            0,
                            null));

            // Admitted, and then narrowed: ClaudeUsageService.dashboardFor
            // gives them their own row only. The filter chain's job is to let
            // them reach it; the scoping is asserted in ClaudeUsageServiceTest.
            mvc.perform(get("/api/v1/claude-usage").with(as(AppRole.USER)))
                    .andExpect(status().isOk());
        }

        @Test
        void the_roster_mutations_still_refuse_a_plain_user() throws Exception {
            // The half that did not move. Reading your own usage is not a
            // licence to change somebody's dashboard role, budget or tracking.
            mvc.perform(patch("/api/v1/admin/claude-usage/members/" + java.util.UUID.randomUUID())
                            .with(as(AppRole.USER))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"teamRole\":\"admin\"}"))
                    .andExpect(status().isForbidden());

            mvc.perform(post("/api/v1/admin/claude-usage/members/" + java.util.UUID.randomUUID() + "/remove")
                            .with(as(AppRole.USER)))
                    .andExpect(status().isForbidden());
        }

        @Test
        void member_activity_refuses_a_plain_user_and_admits_an_admin() throws Exception {
            // One colleague's day-by-day usage: an admin read, never a plain
            // user's, even though the team dashboard itself admits them.
            java.util.UUID id = java.util.UUID.randomUUID();
            mvc.perform(get("/api/v1/admin/claude-usage/members/" + id + "/activity").with(as(AppRole.USER)))
                    .andExpect(status().isForbidden());

            org.mockito.Mockito.when(claudeUsageService.memberActivity(
                            org.mockito.ArgumentMatchers.eq(id),
                            org.mockito.ArgumentMatchers.any(),
                            org.mockito.ArgumentMatchers.anyInt(),
                            org.mockito.ArgumentMatchers.any(),
                            org.mockito.ArgumentMatchers.any(),
                            org.mockito.ArgumentMatchers.any()))
                    .thenReturn(new com.coc.docs.claudeusage.ClaudeUsageMemberActivity(
                            id, java.time.LocalDate.now(), java.time.LocalDate.now(), List.of()));

            mvc.perform(get("/api/v1/admin/claude-usage/members/" + id + "/activity").with(as(AppRole.ADMIN)))
                    .andExpect(status().isOk());
        }

        @Test
        void the_dashboard_admits_an_admin() throws Exception {
            org.mockito.Mockito.when(claudeUsageService.dashboardFor(
                            org.mockito.ArgumentMatchers.any(),
                            org.mockito.ArgumentMatchers.any(),
                            org.mockito.ArgumentMatchers.anyInt()))
                    .thenReturn(new com.coc.docs.claudeusage.ClaudeUsageDashboard(
                            "week",
                            java.time.LocalDate.now(),
                            java.time.LocalDate.now(),
                            List.of(),
                            List.of(),
                            new com.coc.docs.claudeusage.ClaudeUsageTotals(0, 0, 0, 0, java.math.BigDecimal.ZERO),
                            0,
                            0,
                            null));

            mvc.perform(get("/api/v1/claude-usage").with(as(AppRole.ADMIN)))
                    .andExpect(status().isOk());
        }

        @Test
        void the_dashboard_admits_a_super_admin_too() throws Exception {
            // Not a fixed floor above admin (unlike /admin/accounts) — both
            // roles manage this roster.
            org.mockito.Mockito.when(claudeUsageService.dashboardFor(
                            org.mockito.ArgumentMatchers.any(),
                            org.mockito.ArgumentMatchers.any(),
                            org.mockito.ArgumentMatchers.anyInt()))
                    .thenReturn(new com.coc.docs.claudeusage.ClaudeUsageDashboard(
                            "week",
                            java.time.LocalDate.now(),
                            java.time.LocalDate.now(),
                            List.of(),
                            List.of(),
                            new com.coc.docs.claudeusage.ClaudeUsageTotals(0, 0, 0, 0, java.math.BigDecimal.ZERO),
                            0,
                            0,
                            null));

            mvc.perform(get("/api/v1/claude-usage").with(as(AppRole.SUPER_ADMIN)))
                    .andExpect(status().isOk());
        }

        @Test
        void the_dashboard_refuses_a_caller_with_no_session() throws Exception {
            mvc.perform(get("/api/v1/claude-usage")).andExpect(status().isUnauthorized());
        }

        @Test
        void ingest_is_reachable_without_a_session() throws Exception {
            // permitAll() at the filter-chain level (ClaudeUsageIngestController
            // is authorized by its own bearer-credential check, never by a
            // session) — proven here by 401 for a missing Authorization header,
            // which only happens if the filter chain let the request through to
            // the controller. (ADR-047: a 503 "not configured" no longer exists
            // — with no shared secret configured in this slice, the controller
            // falls through to the per-account token check and refuses with the
            // same 401 an invalid credential of either shape gets.)
            mvc.perform(post("/api/ingest")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(
                                    "{\"email\":\"a@example.com\",\"date\":\"2026-01-01\","
                                            + "\"input\":0,\"output\":0,\"cacheRead\":0,\"cacheCreation\":0}"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        void ingest_scan_is_reachable_without_a_session() throws Exception {
            // Same permitAll() as /api/ingest (ADR-095): 401 for a missing
            // Authorization header proves the request reached the controller's
            // own bearer check rather than being stopped by the filter chain.
            mvc.perform(post("/api/ingest/scan")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"a@example.com\"}"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        void ingest_scan_log_is_reachable_without_a_session() throws Exception {
            // Same permitAll() as /api/ingest/scan (ADR-096): the 401 comes from
            // the controller's own bearer check, past the filter chain.
            mvc.perform(post("/api/ingest/scan-log")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"scanId\":\"" + java.util.UUID.randomUUID() + "\",\"email\":\"a@example.com\","
                                    + "\"startedAt\":\"2026-10-07T04:00:00Z\",\"finishedAt\":\"2026-10-07T04:00:08Z\"}"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        void scan_logs_are_admin_only() throws Exception {
            mvc.perform(get("/api/v1/admin/claude-usage/scan-logs")).andExpect(status().isUnauthorized());
            mvc.perform(get("/api/v1/admin/claude-usage/scan-logs").with(as(AppRole.USER)))
                    .andExpect(status().isForbidden());
        }

        @Test
        void my_dashboard_refuses_a_caller_with_no_session() throws Exception {
            mvc.perform(get("/api/v1/claude-usage/me")).andExpect(status().isUnauthorized());
        }

        @Test
        void my_dashboard_admits_a_plain_user() throws Exception {
            // The whole point of ADR-052: unlike the team-wide roster above
            // (ADMIN floor), a plain user reads their own usage here.
            org.mockito.Mockito.when(claudeUsageService.myDashboard(
                            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt()))
                    .thenReturn(new com.coc.docs.claudeusage.ClaudeUsageMyDashboard(
                            "user@example.com", "Test", java.time.LocalDate.now(), 0, 0, List.of(), null, null,
                            com.coc.docs.claudeusage.ClaudeUsageMyDashboard.ModelBreakdown.empty(
                                    7, java.time.LocalDate.now(), java.time.LocalDate.now()),
                            new com.coc.docs.claudeusage.ClaudeUsageMyDashboard.WeekUsage(
                                    java.time.LocalDate.now(), java.time.LocalDate.now(), 0, 0, 0),
                            java.math.BigDecimal.ZERO, null));

            mvc.perform(get("/api/v1/claude-usage/me").with(as(AppRole.USER)))
                    .andExpect(status().isOk());
        }

        @Test
        void my_dashboard_admits_an_admin_too() throws Exception {
            org.mockito.Mockito.when(claudeUsageService.myDashboard(
                            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt()))
                    .thenReturn(new com.coc.docs.claudeusage.ClaudeUsageMyDashboard(
                            "admin@example.com", "Test", java.time.LocalDate.now(), 0, 0, List.of(), null, null,
                            com.coc.docs.claudeusage.ClaudeUsageMyDashboard.ModelBreakdown.empty(
                                    7, java.time.LocalDate.now(), java.time.LocalDate.now()),
                            new com.coc.docs.claudeusage.ClaudeUsageMyDashboard.WeekUsage(
                                    java.time.LocalDate.now(), java.time.LocalDate.now(), 0, 0, 0),
                            java.math.BigDecimal.ZERO, null));

            mvc.perform(get("/api/v1/claude-usage/me").with(as(AppRole.ADMIN)))
                    .andExpect(status().isOk());
        }

        @Test
        void usage_tokens_refuses_a_caller_with_no_session() throws Exception {
            mvc.perform(get("/api/v1/me/usage-tokens")).andExpect(status().isUnauthorized());
        }

        @Test
        void usage_tokens_refuses_a_guest() throws Exception {
            // Floored at USER (ADR-053, widened from ADMIN) — a guest
            // (ADR-017's pending/unapproved rank) still may not mint or list
            // a token, but any actual member may, self-service.
            mvc.perform(get("/api/v1/me/usage-tokens").with(as(AppRole.GUEST))).andExpect(status().isForbidden());
        }

        @Test
        void usage_tokens_admits_a_plain_user() throws Exception {
            // The whole point of ADR-053: a plain user can self-mint the
            // token their own "Download push-usage.mjs" button needs,
            // without asking an admin for one.
            org.mockito.Mockito.when(claudeUsageIngestTokenService.list(org.mockito.ArgumentMatchers.any()))
                    .thenReturn(List.of());

            mvc.perform(get("/api/v1/me/usage-tokens").with(as(AppRole.USER))).andExpect(status().isOk());
        }

        @Test
        void usage_tokens_admits_an_admin() throws Exception {
            org.mockito.Mockito.when(claudeUsageIngestTokenService.list(org.mockito.ArgumentMatchers.any()))
                    .thenReturn(List.of());

            mvc.perform(get("/api/v1/me/usage-tokens").with(as(AppRole.ADMIN))).andExpect(status().isOk());
        }

        @Test
        void cli_token_exchange_is_reachable_without_a_session() throws Exception {
            // permitAll() (ADR-060): a CLI has no session. The 400 comes from the
            // service's own invalid_grant, which only happens if the chain let
            // the request through to the controller.
            org.mockito.Mockito.when(claudeUsageCliAuthService.exchange(
                            org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
                    .thenThrow(ApiException.badRequest("invalid_grant"));

            mvc.perform(post("/api/cli/token")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"code\":\"c\",\"codeVerifier\":\"v\"}"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void cli_token_exchange_is_post_only() throws Exception {
            mvc.perform(get("/api/cli/token")).andExpect(status().isUnauthorized());
        }

        @Test
        void cli_authorization_refuses_a_caller_with_no_session() throws Exception {
            mvc.perform(post("/api/v1/me/usage-tokens/cli-authorizations")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"codeChallenge\":\"x\",\"device\":\"d\"}"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        void cli_authorization_refuses_a_guest() throws Exception {
            mvc.perform(post("/api/v1/me/usage-tokens/cli-authorizations").with(as(AppRole.GUEST))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"codeChallenge\":\"x\",\"device\":\"d\"}"))
                    .andExpect(status().isForbidden());
        }

        @Test
        void cli_authorization_admits_a_plain_user() throws Exception {
            org.mockito.Mockito.when(claudeUsageCliAuthService.approve(
                            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString(),
                            org.mockito.ArgumentMatchers.any()))
                    .thenReturn("one-time-code");

            mvc.perform(post("/api/v1/me/usage-tokens/cli-authorizations").with(as(AppRole.USER))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"codeChallenge\":\"x\",\"device\":\"d\"}"))
                    .andExpect(status().isCreated())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                            .jsonPath("$.code").value("one-time-code"));
        }

        private void stubMint() {
            java.util.UUID id = java.util.UUID.randomUUID();
            org.mockito.Mockito.when(claudeUsageIngestTokenService.create(
                            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString()))
                    .thenReturn(new com.coc.docs.claudeusage.ClaudeUsageIngestTokenService.MintedToken(
                            new com.coc.docs.claudeusage.ClaudeUsageIngestToken(
                                    id, java.util.UUID.randomUUID(), "hash", "Laptop",
                                    java.time.Instant.now(), null, null),
                            "coc_fixture")); // gitleaks:allow — fixture, not a credential
        }

        @Test
        void minting_a_token_returns_the_local_ingest_url_in_development() throws Exception {
            // The personalized push-usage.mjs embeds this verbatim (ADR-050).
            stubMint();

            mvc.perform(post("http://localhost:8080/api/v1/me/usage-tokens").with(as(AppRole.USER))
                            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                            .content("{\"label\":\"Laptop\"}"))
                    .andExpect(status().isCreated())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                            .jsonPath("$.token").value("coc_fixture"))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                            .jsonPath("$.ingestUrl").value("http://localhost:8080/api/ingest"));
        }

        @Test
        void minting_a_token_behind_the_tls_proxy_returns_the_public_https_url_not_localhost() throws Exception {
            stubMint();

            mvc.perform(post("https://docs.example.com/api/v1/me/usage-tokens").with(as(AppRole.USER))
                            .with(r -> { r.setScheme("http"); r.setServerPort(80); r.setSecure(false); return r; })
                            .header("X-Forwarded-Proto", "https")
                            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                            .content("{\"label\":\"Laptop\"}"))
                    .andExpect(status().isCreated())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                            .jsonPath("$.ingestUrl").value("https://docs.example.com/api/ingest"));
        }
    }

    @Nested
    @DisplayName("Attendance (admin surface over HRMS reports)")
    class AttendancePath {

        @Test
        void reports_for_date_refuses_a_plain_user() throws Exception {
            // Falls through to the generic /api/v1/admin/** rule (admin
            // floor), same as Claude Usage — no floor of its own.
            mvc.perform(get("/api/v1/admin/attendance/reports")
                            .param("date", "2026-09-07")
                            .with(as(AppRole.USER)))
                    .andExpect(status().isForbidden());
        }

        @Test
        void reports_for_date_admits_an_admin() throws Exception {
            org.mockito.Mockito.when(hrmsNotificationService.reportsForDate(java.time.LocalDate.of(2026, 9, 7)))
                    .thenReturn(List.of());

            mvc.perform(get("/api/v1/admin/attendance/reports")
                            .param("date", "2026-09-07")
                            .with(as(AppRole.ADMIN)))
                    .andExpect(status().isOk());
        }

        @Test
        void reports_for_date_admits_a_super_admin_too() throws Exception {
            org.mockito.Mockito.when(hrmsNotificationService.reportsForDate(java.time.LocalDate.of(2026, 9, 7)))
                    .thenReturn(List.of());

            mvc.perform(get("/api/v1/admin/attendance/reports")
                            .param("date", "2026-09-07")
                            .with(as(AppRole.SUPER_ADMIN)))
                    .andExpect(status().isOk());
        }

        @Test
        void reports_for_date_refuses_a_caller_with_no_session() throws Exception {
            mvc.perform(get("/api/v1/admin/attendance/reports").param("date", "2026-09-07"))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Nested
    @DisplayName("My Attendance (ADR-086)")
    class MyAttendancePath {

        private com.coc.docs.notify.HrmsNotificationService.ScopedReport emptyScopedReport() {
            return new com.coc.docs.notify.HrmsNotificationService.ScopedReport(
                    new com.coc.docs.notify.HrmsMailContent(
                            null, java.time.LocalDate.of(2026, 9, 7), null, List.of(), List.of(), null),
                    true,
                    false);
        }

        @ParameterizedTest
        @EnumSource(value = AppRole.class, names = {"USER", "DEVELOPER", "ADMIN", "SUPER_ADMIN"})
        void admits_every_signed_in_role(AppRole role) throws Exception {
            // Own-prefix, USER floor (same pattern as Claude Usage's own read
            // route) — reachable by every signed-in role, including the two
            // ADR-086 blocks from the admin app entirely. Scoping to the
            // caller happens inside the service, not the floor.
            org.mockito.Mockito.when(hrmsNotificationService.myAttendanceFor(
                            org.mockito.ArgumentMatchers.eq(java.time.LocalDate.of(2026, 9, 7)),
                            org.mockito.ArgumentMatchers.any()))
                    .thenReturn(emptyScopedReport());

            mvc.perform(get("/api/v1/me/attendance").param("date", "2026-09-07").with(as(role)))
                    .andExpect(status().isOk());
        }

        @Test
        void refuses_a_caller_with_no_session() throws Exception {
            mvc.perform(get("/api/v1/me/attendance").param("date", "2026-09-07"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        void passes_the_caller_through_to_the_service_rather_than_a_request_parameter() throws Exception {
            // TM-025: there is no parameter on this route that could widen it
            // — the service resolves the caller from the session alone.
            org.mockito.Mockito.when(hrmsNotificationService.myAttendanceFor(
                            org.mockito.ArgumentMatchers.eq(java.time.LocalDate.of(2026, 9, 7)),
                            org.mockito.ArgumentMatchers.any()))
                    .thenReturn(emptyScopedReport());

            mvc.perform(get("/api/v1/me/attendance")
                            .param("date", "2026-09-07")
                            .param("accountId", "11111111-1111-1111-1111-111111111111")
                            .with(as(AppRole.USER)))
                    .andExpect(status().isOk());

            org.mockito.Mockito.verifyNoMoreInteractions(
                    org.mockito.Mockito.ignoreStubs(hrmsNotificationService));
        }
    }

    @Nested
    @DisplayName("My Security (ADR-093)")
    class MySecurityPath {

        @ParameterizedTest
        @EnumSource(value = AppRole.class, names = {"USER", "DEVELOPER", "ADMIN", "SUPER_ADMIN"})
        void admits_every_signed_in_role(AppRole role) throws Exception {
            // Same shape as My Attendance above: own prefix, USER floor,
            // reachable by every signed-in role including the two ADR-086
            // blocks from the admin app entirely.
            org.mockito.Mockito.when(sessionService.recentSignIns(
                            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt()))
                    .thenReturn(List.of());

            mvc.perform(get("/api/v1/me/security").with(as(role))).andExpect(status().isOk());
        }

        @Test
        void refuses_a_caller_with_no_session() throws Exception {
            mvc.perform(get("/api/v1/me/security")).andExpect(status().isUnauthorized());
        }

        @Test
        void passes_the_caller_through_to_the_service_rather_than_a_request_parameter() throws Exception {
            // TM-025: there is no parameter on this route at all — scope comes
            // from the session alone.
            org.mockito.Mockito.when(sessionService.recentSignIns(
                            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt()))
                    .thenReturn(List.of());

            mvc.perform(get("/api/v1/me/security")
                            .param("accountId", "11111111-1111-1111-1111-111111111111")
                            .with(as(AppRole.USER)))
                    .andExpect(status().isOk());
        }
    }

    @Nested
    @DisplayName("default deny")
    class DefaultDeny {

        @ParameterizedTest
        @ValueSource(strings = {
            "/api/v1/undeclared",
            "/api/v1/internal/metrics",
            "/actuator/health",
            "/api/v2/pages"
        })
        void an_undeclared_route_is_not_open_to_a_user(String path) throws Exception {
            // The property that makes one chokepoint work: a route nobody
            // remembered to declare is reachable only by a super admin, which is
            // conspicuous enough to be found immediately.
            //
            // 403 exactly, not "403 or 404". Authorisation runs before dispatch,
            // so a denied caller never reaches the point where "no such handler"
            // could be the answer — and accepting 404 would let the real hole
            // through, because a route wrongly declared permitAll answers 404
            // when no controller matches it. // req: TM-019
            mvc.perform(get(path).with(as(AppRole.USER)))
                    .andExpect(result -> {
                        int actual = result.getResponse().getStatus();
                        if (actual != 403) {
                            throw new AssertionError(
                                    "undeclared route " + path + " answered " + actual
                                            + "; an undeclared route must be denied with 403, not "
                                            + "served and not 404 — a 404 means authorisation let "
                                            + "the request through to the dispatcher");
                        }
                    });
        }

        @Test
        void an_undeclared_route_is_not_open_to_an_anonymous_caller() throws Exception {
            mvc.perform(get("/api/v1/undeclared")).andExpect(status().isUnauthorized());
        }
    }

    @Nested
    @DisplayName("the role is never taken from the client")
    class RoleIsServerSide {

        @Test
        void a_role_header_does_not_grant_anything() throws Exception {
            // TM-003, which matters more now than in the single-service design:
            // there is a network hop an attacker could target, and the role is
            // read from the account row rather than from anything sent.
            mvc.perform(get("/api/v1/admin/accounts")
                            .with(as(AppRole.USER))
                            .header("X-Role", "super_admin")
                            .header("Authorization", "Bearer forged"))
                    .andExpect(status().isForbidden());
        }

        @Test
        void a_session_cookie_that_resolves_to_nothing_authenticates_nobody() throws Exception {
            org.mockito.Mockito.when(sessionService.resolve(org.mockito.ArgumentMatchers.anyString()))
                    .thenReturn(Optional.empty());

            mvc.perform(get("/api/v1/sections")
                            .cookie(new jakarta.servlet.http.Cookie(
                                    SessionService.COOKIE_NAME, "made-up-token")))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Nested
    @DisplayName("the notification stream")
    class NotificationStreamRoute {

        @Test
        void is_reachable_by_any_signed_in_account() throws Exception {
            // The rule this pins: a live stream is a long-lived response, but
            // the authorisation question is an ordinary one asked once, before
            // it opens. On 2026-09-16 this route answered Access Denied to a
            // signed-in caller and the cause was worth being able to bisect
            // here rather than in a browser.
            mvc.perform(get("/api/v1/notifications/stream").with(as(AppRole.USER)))
                    .andExpect(status().isOk());
        }

        @Test
        void is_reachable_by_an_admin_too() throws Exception {
            mvc.perform(get("/api/v1/notifications/stream").with(as(AppRole.ADMIN)))
                    .andExpect(status().isOk());
        }

        @Test
        void is_refused_without_a_session() throws Exception {
            // It carries one account's own events, so there is no anonymous
            // form of this request that could mean anything.
            mvc.perform(get("/api/v1/notifications/stream")).andExpect(status().isUnauthorized());
        }

        // The async dispatch — the second pass that actually delivers this
        // response — is deliberately not tested here. MockMvc reinstalls the
        // test's SecurityContext on every dispatch, so it is authenticated on
        // both passes whatever the filters do, and a case written here would
        // pass with the bug present. Verified by reintroducing it.
        // NotificationStreamAuthIntegrationTest covers it over real HTTP.
    }

    @Nested
    @DisplayName("the notification inbox")
    class NotificationInbox {

        @ParameterizedTest
        @ValueSource(strings = {"USER", "ADMIN", "SUPER_ADMIN"})
        void is_reachable_by_every_signed_in_role(String role) throws Exception {
            // Every role receives notifications — a new-device sign-in alert
            // goes to whoever was signed in to, whatever else they may do here
            // (ADR-034). A floor above `user` would leave the people most
            // likely to be targeted unable to read their own alerts.
            mvc.perform(get("/api/v1/notifications/inbox").with(as(AppRole.valueOf(role))))
                    .andExpect(status().isOk());
        }

        @Test
        void is_refused_without_a_session() throws Exception {
            // The rows are scoped to the resolved caller, so an anonymous form
            // of this request could not mean anything.
            mvc.perform(get("/api/v1/notifications/inbox")).andExpect(status().isUnauthorized());
        }

        @Test
        void marking_read_is_refused_without_a_session() throws Exception {
            mvc.perform(post("/api/v1/notifications/inbox/"
                            + java.util.UUID.randomUUID() + "/read"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        void marking_all_read_needs_a_session_and_nothing_more() throws Exception {
            mvc.perform(post("/api/v1/notifications/inbox/read-all").with(as(AppRole.USER)))
                    .andExpect(status().isNoContent());
        }
    }

    @Nested
    @DisplayName("notification preferences (ADR-042)")
    class NotificationPreferences {

        @ParameterizedTest
        @ValueSource(strings = {"USER", "ADMIN", "SUPER_ADMIN"})
        void is_reachable_by_every_signed_in_role(String role) throws Exception {
            // Every role can receive some notification type, so every role
            // needs to be able to see and change its own preferences — same
            // floor as the inbox above, for the same reason.
            mvc.perform(get("/api/v1/notifications/preferences").with(as(AppRole.valueOf(role))))
                    .andExpect(status().isOk());
        }

        @Test
        void is_refused_without_a_session() throws Exception {
            // The account is the resolved caller's, so an anonymous form of
            // this request could not mean anything.
            mvc.perform(get("/api/v1/notifications/preferences")).andExpect(status().isUnauthorized());
        }

        @Test
        void updating_a_preference_is_refused_without_a_session() throws Exception {
            mvc.perform(put("/api/v1/notifications/preferences/registration.pending")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"enabled\":false}"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        void updating_a_preference_needs_a_session_and_nothing_more() throws Exception {
            mvc.perform(put("/api/v1/notifications/preferences/registration.pending")
                            .with(as(AppRole.USER))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"enabled\":false}"))
                    .andExpect(status().isNoContent());
        }
    }

    @Nested
    @DisplayName("AX Renewal (ADR-086, revised for this feature by ADR-093)")
    class AxRenewalPath {

        /** Reached rather than refused at the security layer — whatever the stubbed service then answers. */
        private org.springframework.test.web.servlet.ResultMatcher reached() {
            return result -> org.assertj.core.api.Assertions.assertThat(result.getResponse().getStatus())
                    .as("refused at the security layer")
                    .isNotIn(401, 403);
        }

        @ParameterizedTest
        @EnumSource(value = AppRole.class, names = {"GUEST", "USER"})
        void the_read_side_refuses_user_and_guest(AppRole role) throws Exception {
            // ADR-093: the floor is DEVELOPER by rank — a plain user is still
            // refused, as under ADR-086.
            mvc.perform(get("/api/v1/ax-renewal/projects").with(as(role))).andExpect(status().isForbidden());
        }

        @ParameterizedTest
        @EnumSource(value = AppRole.class, names = {"DEVELOPER", "REVIEWER", "LEAD"})
        void the_read_side_admits_developer_reviewer_and_lead(AppRole role) throws Exception {
            // ADR-093 restores ADR-068's workspace access for these three;
            // which projects each sees is membership (ADR-070).
            org.mockito.Mockito.when(axRenewalService.list(org.mockito.ArgumentMatchers.any())).thenReturn(List.of());

            mvc.perform(get("/api/v1/ax-renewal/projects").with(as(role))).andExpect(status().isOk());
        }

        @Test
        void the_read_side_admits_an_admin() throws Exception {
            org.mockito.Mockito.when(axRenewalService.list(org.mockito.ArgumentMatchers.any())).thenReturn(List.of());

            mvc.perform(get("/api/v1/ax-renewal/projects").with(as(AppRole.ADMIN)))
                    .andExpect(status().isOk());
        }

        @Test
        void the_read_side_refuses_a_caller_with_no_session() throws Exception {
            mvc.perform(get("/api/v1/ax-renewal/projects")).andExpect(status().isUnauthorized());
        }

        @Test
        void creating_a_project_refuses_a_plain_user() throws Exception {
            mvc.perform(post("/api/v1/ax-renewal/projects")
                            .with(as(AppRole.USER))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"x\"}"))
                    .andExpect(status().isForbidden());
        }

        @Test
        void creating_a_project_admits_an_admin() throws Exception {
            org.mockito.Mockito.when(axRenewalService.create(
                            org.mockito.ArgumentMatchers.any(),
                            org.mockito.ArgumentMatchers.any(),
                            org.mockito.ArgumentMatchers.any()))
                    .thenThrow(ApiException.badRequest("stub"));

            // 400 from the stub above, not 403/401 — proof the route was
            // reached rather than refused at the security layer.
            mvc.perform(post("/api/v1/ax-renewal/projects")
                            .with(as(AppRole.ADMIN))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"x\"}"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void deleting_a_project_refuses_a_plain_user() throws Exception {
            mvc.perform(delete("/api/v1/ax-renewal/projects/" + UUID.randomUUID()).with(as(AppRole.USER)))
                    .andExpect(status().isForbidden());
        }

        @Test
        void deleting_a_project_refuses_a_caller_with_no_session() throws Exception {
            mvc.perform(delete("/api/v1/ax-renewal/projects/" + UUID.randomUUID()))
                    .andExpect(status().isUnauthorized());
        }

        // --- Renaming a project (ADR-076): the same floor as deleting one.

        @ParameterizedTest
        @EnumSource(value = AppRole.class, names = {"USER", "DEVELOPER", "REVIEWER", "LEAD"})
        void renaming_a_project_refuses_every_role_below_admin(AppRole role) throws Exception {
            mvc.perform(patch("/api/v1/ax-renewal/projects/" + UUID.randomUUID())
                            .with(as(role))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"Renamed\"}"))
                    .andExpect(status().isForbidden());
        }

        @Test
        void renaming_a_project_admits_an_admin() throws Exception {
            org.mockito.Mockito.doThrow(ApiException.notFound("project"))
                    .when(axRenewalService)
                    .updateDetails(
                            org.mockito.ArgumentMatchers.any(),
                            org.mockito.ArgumentMatchers.any(),
                            org.mockito.ArgumentMatchers.any(),
                            org.mockito.ArgumentMatchers.any());

            // 404 from the stub, not 403 — the route was reached.
            mvc.perform(patch("/api/v1/ax-renewal/projects/" + UUID.randomUUID())
                            .with(as(AppRole.ADMIN))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"Renamed\",\"description\":\"notes\"}"))
                    .andExpect(status().isNotFound());
        }

        @Test
        void renaming_a_project_to_a_blank_name_is_refused_before_it_reaches_the_service() throws Exception {
            mvc.perform(patch("/api/v1/ax-renewal/projects/" + UUID.randomUUID())
                            .with(as(AppRole.ADMIN))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"   \"}"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void deleting_a_project_admits_an_admin() throws Exception {
            org.mockito.Mockito.doThrow(ApiException.notFound("project"))
                    .when(axRenewalService)
                    .delete(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());

            // 404 from the stub, not 403 — proof the route was reached rather
            // than refused at the security layer.
            mvc.perform(delete("/api/v1/ax-renewal/projects/" + UUID.randomUUID()).with(as(AppRole.ADMIN)))
                    .andExpect(status().isNotFound());
        }

        @Test
        void adding_a_comment_refuses_a_plain_user() throws Exception {
            mvc.perform(post("/api/v1/ax-renewal/projects/" + UUID.randomUUID() + "/comments")
                            .with(as(AppRole.USER))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"body\":\"looks right to me\"}"))
                    .andExpect(status().isForbidden());
        }

        @Test
        void adding_a_comment_admits_an_admin() throws Exception {
            org.mockito.Mockito.doThrow(ApiException.notFound("project"))
                    .when(axRenewalService)
                    .addComment(
                            org.mockito.ArgumentMatchers.any(),
                            org.mockito.ArgumentMatchers.any(),
                            org.mockito.ArgumentMatchers.any());

            // 404 from the stub, not 403 — the route was reached.
            mvc.perform(post("/api/v1/ax-renewal/projects/" + UUID.randomUUID() + "/comments")
                            .with(as(AppRole.ADMIN))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"body\":\"looks right to me\"}"))
                    .andExpect(status().isNotFound());
        }

        @ParameterizedTest
        @EnumSource(value = AppRole.class, names = {"DEVELOPER", "REVIEWER", "LEAD"})
        void adding_a_comment_admits_developer_reviewer_and_lead(AppRole role) throws Exception {
            org.mockito.Mockito.doThrow(ApiException.notFound("project"))
                    .when(axRenewalService)
                    .addComment(
                            org.mockito.ArgumentMatchers.any(),
                            org.mockito.ArgumentMatchers.any(),
                            org.mockito.ArgumentMatchers.any());

            mvc.perform(post("/api/v1/ax-renewal/projects/" + UUID.randomUUID() + "/comments")
                            .with(as(role))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"body\":\"looks right to me\"}"))
                    .andExpect(status().isNotFound());
        }

        @Test
        void removing_a_comment_refuses_a_plain_user() throws Exception {
            // The comment routes share the read side's DEVELOPER floor (ADR-093).
            mvc.perform(delete("/api/v1/ax-renewal/projects/" + UUID.randomUUID() + "/comments/"
                                    + UUID.randomUUID())
                            .with(as(AppRole.USER)))
                    .andExpect(status().isForbidden());
        }

        @Test
        void removing_a_comment_admits_an_admin_and_leaves_ownership_to_the_service() throws Exception {
            // The floor is all a matcher can decide here: whether this caller
            // may remove *this* comment depends on who wrote it, which is row
            // data. AxRenewalCommentDeleteTest covers that half (ADR-045).
            org.mockito.Mockito.doThrow(ApiException.notFound("comment"))
                    .when(axRenewalService)
                    .deleteComment(
                            org.mockito.ArgumentMatchers.any(),
                            org.mockito.ArgumentMatchers.any(),
                            org.mockito.ArgumentMatchers.any());

            mvc.perform(delete("/api/v1/ax-renewal/projects/" + UUID.randomUUID() + "/comments/"
                                    + UUID.randomUUID())
                            .with(as(AppRole.ADMIN)))
                    .andExpect(status().isNotFound());
        }

        @Test
        void removing_a_comment_refuses_a_caller_with_no_session() throws Exception {
            mvc.perform(delete("/api/v1/ax-renewal/projects/" + UUID.randomUUID() + "/comments/"
                            + UUID.randomUUID()))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        void assistant_chat_refuses_a_plain_user() throws Exception {
            // Chat can point a local model at the source folder, the same as
            // Run analysis — so it sits at the analysis floor, not at the
            // comment floor it superficially resembles (ADR-046).
            mvc.perform(post("/api/v1/ax-renewal/projects/" + UUID.randomUUID() + "/assistant/chat")
                            .with(as(AppRole.USER))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"message\":\"what runs this?\"}"))
                    .andExpect(status().isForbidden());
        }

        @Test
        void assistant_chat_refuses_a_caller_with_no_session() throws Exception {
            mvc.perform(post("/api/v1/ax-renewal/projects/" + UUID.randomUUID() + "/assistant/chat")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"message\":\"what runs this?\"}"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        void assistant_chat_admits_an_admin() throws Exception {
            org.mockito.Mockito.doThrow(ApiException.notFound("project"))
                    .when(axRenewalService)
                    .chat(
                            org.mockito.ArgumentMatchers.any(),
                            org.mockito.ArgumentMatchers.any(),
                            org.mockito.ArgumentMatchers.any(),
                            org.mockito.ArgumentMatchers.any(),
                            org.mockito.ArgumentMatchers.any());

            // 404 from the stub, not 403 — the route was reached.
            mvc.perform(post("/api/v1/ax-renewal/projects/" + UUID.randomUUID() + "/assistant/chat")
                            .with(as(AppRole.ADMIN))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"message\":\"what runs this?\"}"))
                    .andExpect(status().isNotFound());
        }

        @Test
        void assistant_chat_rejects_an_empty_message_before_it_reaches_a_model() throws Exception {
            mvc.perform(post("/api/v1/ax-renewal/projects/" + UUID.randomUUID() + "/assistant/chat")
                            .with(as(AppRole.ADMIN))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"message\":\"   \"}"))
                    .andExpect(status().is4xxClientError());
        }

        @Test
        void confirming_a_section_admits_a_caller_holding_only_the_developer_role() throws Exception {
            // ADR-093 restores ADR-040's carve-out: holding Developer
            // *exactly* admits the write actions, with no Admin rank.
            org.mockito.Mockito.doThrow(ApiException.notFound("project"))
                    .when(axRenewalService)
                    .confirmSection(
                            org.mockito.ArgumentMatchers.any(),
                            org.mockito.ArgumentMatchers.any(),
                            org.mockito.ArgumentMatchers.anyInt(),
                            org.mockito.ArgumentMatchers.any());

            mvc.perform(post("/api/v1/ax-renewal/projects/" + UUID.randomUUID() + "/plan/stack_move/confirm")
                            .with(SecurityMockMvcRequestPostProcessors.authentication(
                                    com.coc.docs.auth.TestCallers.authentication(
                                            java.util.Set.of(AppRole.DEVELOPER))))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"revision\":0}"))
                    .andExpect(status().isNotFound());
        }

        @ParameterizedTest
        @org.junit.jupiter.params.provider.ValueSource(strings = {
            "/plan/pages/confirm",
            "/plan/pages/reopen",
            "/implementation/start",
            "/implementation/RUN/approve",
            "/implementation/RUN/reject",
            "/implementation/RUN/cancel",
            "/implementation/error-fix/start",
            "/implementation/ui-change/start",
            "/implementation/RUN/run-tests",
            "/implementation/RUN/revert",
            "/run-locally/start",
            "/run-locally/stop"
        })
        void a_developer_reaches_every_write_action(String route) throws Exception {
            // Exact Developer (ADR-093) — the same twelve routes ADR-086 had
            // closed. Whatever the stubbed service answers, it is not a 401/403.
            mvc.perform(post("/api/v1/ax-renewal/projects/" + UUID.randomUUID()
                                    + route.replace("RUN", UUID.randomUUID().toString()))
                            .with(as(AppRole.DEVELOPER))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"revision\":0}"))
                    .andExpect(reached());
        }

        @Test
        void confirming_a_section_admits_a_super_admin() throws Exception {
            // doThrow().when(...), not when(...).thenThrow(...): the mock is
            // re-stubbed for this exact method across multiple tests in this
            // class, and when(mock.method()) must invoke the method to
            // record it -- which, once already stubbed to throw, throws
            // immediately and never reaches thenThrow(). doThrow() configures
            // the answer before the call, side-stepping that trap.
            org.mockito.Mockito.doThrow(ApiException.notFound("project"))
                    .when(axRenewalService)
                    .confirmSection(
                            org.mockito.ArgumentMatchers.any(),
                            org.mockito.ArgumentMatchers.any(),
                            org.mockito.ArgumentMatchers.anyInt(),
                            org.mockito.ArgumentMatchers.any());

            mvc.perform(post("/api/v1/ax-renewal/projects/" + UUID.randomUUID() + "/plan/stack_move/confirm")
                            .with(SecurityMockMvcRequestPostProcessors.authentication(
                                    com.coc.docs.auth.TestCallers.authentication(AppRole.SUPER_ADMIN)))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"revision\":0}"))
                    .andExpect(status().isNotFound());
        }

        @Test
        void confirming_a_section_now_admits_a_plain_admin_too() throws Exception {
            // ADR-086 widened the write actions to a plain Admin; ADR-093
            // keeps that alongside exact Developer.
            org.mockito.Mockito.doThrow(ApiException.notFound("project"))
                    .when(axRenewalService)
                    .confirmSection(
                            org.mockito.ArgumentMatchers.any(),
                            org.mockito.ArgumentMatchers.any(),
                            org.mockito.ArgumentMatchers.anyInt(),
                            org.mockito.ArgumentMatchers.any());

            mvc.perform(post("/api/v1/ax-renewal/projects/" + UUID.randomUUID() + "/plan/stack_move/confirm")
                            .with(as(AppRole.ADMIN))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"revision\":0}"))
                    .andExpect(status().isNotFound());
        }

        // --- Project members (ADR-069) ------------------------------------
        // The Admin floor only. Whether the caller is on *this* project is
        // row data — AxRenewalMembershipTest covers that half.

        @ParameterizedTest
        @EnumSource(value = AppRole.class, names = {"USER", "DEVELOPER", "REVIEWER", "LEAD"})
        void managing_members_refuses_every_role_below_admin(AppRole role) throws Exception {
            UUID projectId = UUID.randomUUID();
            mvc.perform(post("/api/v1/ax-renewal/projects/" + projectId + "/members")
                            .with(as(role))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"someone@example.com\"}"))
                    .andExpect(status().isForbidden());
            mvc.perform(delete("/api/v1/ax-renewal/projects/" + projectId + "/members/" + UUID.randomUUID())
                            .with(as(role)))
                    .andExpect(status().isForbidden());
        }

        @Test
        void adding_a_member_admits_an_admin() throws Exception {
            org.mockito.Mockito.doThrow(ApiException.notFound("project"))
                    .when(axRenewalService)
                    .addMember(
                            org.mockito.ArgumentMatchers.any(),
                            org.mockito.ArgumentMatchers.any(),
                            org.mockito.ArgumentMatchers.any());

            // 404 from the stub, not 403 — the route was reached.
            mvc.perform(post("/api/v1/ax-renewal/projects/" + UUID.randomUUID() + "/members")
                            .with(as(AppRole.ADMIN))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"someone@example.com\"}"))
                    .andExpect(status().isNotFound());
        }

        @Test
        void removing_a_member_admits_an_admin() throws Exception {
            org.mockito.Mockito.doThrow(ApiException.notFound("member"))
                    .when(axRenewalService)
                    .removeMember(
                            org.mockito.ArgumentMatchers.any(),
                            org.mockito.ArgumentMatchers.any(),
                            org.mockito.ArgumentMatchers.any());

            mvc.perform(delete("/api/v1/ax-renewal/projects/" + UUID.randomUUID() + "/members/" + UUID.randomUUID())
                            .with(as(AppRole.ADMIN)))
                    .andExpect(status().isNotFound());
        }

        @Test
        void adding_a_member_without_an_email_is_refused_before_it_reaches_the_service() throws Exception {
            mvc.perform(post("/api/v1/ax-renewal/projects/" + UUID.randomUUID() + "/members")
                            .with(as(AppRole.ADMIN))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"  \"}"))
                    .andExpect(status().isBadRequest());
        }

        // --- Reviewer and Lead (ADR-093 restores ADR-068) -----------------
        // In the workspace to read and comment, and nothing else: reading and
        // commenting are covered above; drafting stays at the ADMIN floor and
        // signing off/building at Admin-or-exactly-Developer, neither of which
        // out-ranking Developer satisfies.

        @ParameterizedTest
        @EnumSource(value = AppRole.class, names = {"REVIEWER", "LEAD"})
        void reviewer_and_lead_may_not_draft_the_plan(AppRole role) throws Exception {
            // The admin floor: create, analyse, chat, edit. Rank does not
            // reach it — both roles sit strictly below ADMIN.
            UUID projectId = UUID.randomUUID();
            mvc.perform(post("/api/v1/ax-renewal/projects")
                            .with(as(role))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"x\"}"))
                    .andExpect(status().isForbidden());
            mvc.perform(post("/api/v1/ax-renewal/projects/" + projectId + "/analysis/run").with(as(role)))
                    .andExpect(status().isForbidden());
            mvc.perform(post("/api/v1/ax-renewal/projects/" + projectId + "/assistant/chat")
                            .with(as(role))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"message\":\"what runs this?\"}"))
                    .andExpect(status().isForbidden());
            mvc.perform(put("/api/v1/ax-renewal/projects/" + projectId + "/plan/pages")
                            .with(as(role))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"pages\":[],\"revision\":0}"))
                    .andExpect(status().isForbidden());
            mvc.perform(put("/api/v1/ax-renewal/projects/" + projectId + "/destination")
                            .with(as(role))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"kind\":\"git_repo\",\"target\":\"x\",\"revision\":0}"))
                    .andExpect(status().isForbidden());
        }

        @ParameterizedTest
        @EnumSource(value = AppRole.class, names = {"REVIEWER", "LEAD"})
        void reviewer_and_lead_may_not_sign_off_or_build(AppRole role) throws Exception {
            // Admin or exactly Developer (ADR-093): out-ranking Developer is
            // not holding it.
            UUID projectId = UUID.randomUUID();
            UUID runId = UUID.randomUUID();
            mvc.perform(post("/api/v1/ax-renewal/projects/" + projectId + "/plan/pages/confirm")
                            .with(as(role))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"revision\":0}"))
                    .andExpect(status().isForbidden());
            mvc.perform(post("/api/v1/ax-renewal/projects/" + projectId + "/plan/pages/reopen").with(as(role)))
                    .andExpect(status().isForbidden());
            mvc.perform(post("/api/v1/ax-renewal/projects/" + projectId + "/implementation/start").with(as(role)))
                    .andExpect(status().isForbidden());
            mvc.perform(post("/api/v1/ax-renewal/projects/" + projectId + "/implementation/" + runId + "/approve")
                            .with(as(role)))
                    .andExpect(status().isForbidden());
            mvc.perform(post("/api/v1/ax-renewal/projects/" + projectId + "/implementation/" + runId + "/revert")
                            .with(as(role)))
                    .andExpect(status().isForbidden());
            mvc.perform(post("/api/v1/ax-renewal/projects/" + projectId + "/run-locally/start").with(as(role)))
                    .andExpect(status().isForbidden());
        }

        @Test
        void a_plan_edit_without_a_revision_is_refused_before_it_reaches_the_service() throws Exception {
            // Not an authorisation rule, but this is the one MockMvc slice
            // that has the AX Renewal controller wired: a client that omits
            // the revision is asking to overwrite whatever a teammate saved
            // meanwhile, so it is a 400 — never an implied "latest" (ADR-067,
            // the same stance SaveDraftRequest.versionNo takes).
            mvc.perform(put("/api/v1/ax-renewal/projects/" + UUID.randomUUID() + "/plan/pages")
                            .with(as(AppRole.ADMIN))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"pages\":[]}"))
                    .andExpect(status().isBadRequest());

            org.mockito.Mockito.verify(axRenewalService, org.mockito.Mockito.never())
                    .updatePages(
                            org.mockito.ArgumentMatchers.any(),
                            org.mockito.ArgumentMatchers.any(),
                            org.mockito.ArgumentMatchers.anyInt(),
                            org.mockito.ArgumentMatchers.any());
        }

        @Test
        void confirming_without_a_revision_is_refused_before_it_reaches_the_service() throws Exception {
            mvc.perform(post("/api/v1/ax-renewal/projects/" + UUID.randomUUID() + "/plan/pages/confirm")
                            .with(as(AppRole.ADMIN))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isBadRequest());

            org.mockito.Mockito.verify(axRenewalService, org.mockito.Mockito.never())
                    .confirmSection(
                            org.mockito.ArgumentMatchers.any(),
                            org.mockito.ArgumentMatchers.any(),
                            org.mockito.ArgumentMatchers.anyInt(),
                            org.mockito.ArgumentMatchers.any());
        }

        @ParameterizedTest
        @EnumSource(value = AppRole.class, names = {"USER", "REVIEWER", "LEAD"})
        void starting_implementation_refuses_user_reviewer_and_lead(AppRole role) throws Exception {
            // Admin or exactly Developer (ADR-093); Developer's own admission
            // is a_developer_reaches_every_write_action.
            mvc.perform(post("/api/v1/ax-renewal/projects/" + UUID.randomUUID() + "/implementation/start")
                            .with(as(role)))
                    .andExpect(status().isForbidden());
        }

        @ParameterizedTest
        @EnumSource(value = AppRole.class, names = {"USER", "REVIEWER", "LEAD"})
        void starting_a_local_run_refuses_user_reviewer_and_lead(AppRole role) throws Exception {
            mvc.perform(post("/api/v1/ax-renewal/projects/" + UUID.randomUUID() + "/run-locally/start")
                            .with(as(role)))
                    .andExpect(status().isForbidden());
        }

        @ParameterizedTest
        @EnumSource(value = AppRole.class, names = {"USER", "REVIEWER", "LEAD"})
        void cancelling_a_generate_run_refuses_user_reviewer_and_lead(AppRole role) throws Exception {
            mvc.perform(post("/api/v1/ax-renewal/projects/" + UUID.randomUUID() + "/implementation/"
                            + UUID.randomUUID() + "/cancel")
                            .with(as(role)))
                    .andExpect(status().isForbidden());
        }

        @ParameterizedTest
        @EnumSource(value = AppRole.class, names = {"USER", "REVIEWER", "LEAD"})
        void stopping_a_local_run_refuses_user_reviewer_and_lead(AppRole role) throws Exception {
            mvc.perform(post("/api/v1/ax-renewal/projects/" + UUID.randomUUID() + "/run-locally/stop")
                            .with(as(role)))
                    .andExpect(status().isForbidden());
        }

        @Test
        void a_local_run_s_status_refuses_a_plain_user() throws Exception {
            // The generic AX Renewal read matcher: DEVELOPER by rank (ADR-093).
            mvc.perform(get("/api/v1/ax-renewal/projects/" + UUID.randomUUID() + "/run-locally/status")
                            .with(as(AppRole.USER)))
                    .andExpect(status().isForbidden());
        }

        @ParameterizedTest
        @EnumSource(value = AppRole.class, names = {"DEVELOPER", "REVIEWER", "LEAD"})
        void a_local_run_s_status_admits_developer_reviewer_and_lead(AppRole role) throws Exception {
            org.mockito.Mockito.when(axRenewalService.runLocallyStatus(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                    .thenReturn(new com.coc.docs.axrenewal.AxRenewalLocalRunner.Status(
                            com.coc.docs.axrenewal.AxRenewalLocalRunner.Status.STATE_STOPPED, List.of(), null, null));

            mvc.perform(get("/api/v1/ax-renewal/projects/" + UUID.randomUUID() + "/run-locally/status")
                            .with(as(role)))
                    .andExpect(status().isOk());
        }

        // --- Run history (ADR-078): a plain read, falling under the generic
        // AX Renewal GET matcher — DEVELOPER by rank (ADR-093). Which projects
        // a caller may read beyond that is the service's membership check.

        @Test
        void run_history_refuses_a_plain_user() throws Exception {
            mvc.perform(get("/api/v1/ax-renewal/projects/" + UUID.randomUUID() + "/implementation/runs")
                            .with(as(AppRole.USER)))
                    .andExpect(status().isForbidden());
        }

        @ParameterizedTest
        @EnumSource(value = AppRole.class, names = {"DEVELOPER", "REVIEWER", "LEAD", "ADMIN"})
        void run_history_admits_developer_reviewer_lead_and_admin(AppRole role) throws Exception {
            org.mockito.Mockito.when(axRenewalService.listRuns(
                            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                    .thenReturn(List.of());

            mvc.perform(get("/api/v1/ax-renewal/projects/" + UUID.randomUUID() + "/implementation/runs")
                            .with(as(role)))
                    .andExpect(status().isOk());
        }

        @Test
        void run_history_refuses_a_caller_with_no_session() throws Exception {
            mvc.perform(get("/api/v1/ax-renewal/projects/" + UUID.randomUUID() + "/implementation/runs"))
                    .andExpect(status().isUnauthorized());
        }

        // --- Live generation progress (ADR-082): same floor as run history
        // just above — membership is the service's check.

        @Test
        void generation_progress_refuses_a_plain_user() throws Exception {
            mvc.perform(get("/api/v1/ax-renewal/projects/" + UUID.randomUUID() + "/implementation/"
                                    + UUID.randomUUID() + "/progress")
                            .with(as(AppRole.USER)))
                    .andExpect(status().isForbidden());
        }

        @ParameterizedTest
        @EnumSource(value = AppRole.class, names = {"DEVELOPER", "REVIEWER", "LEAD", "ADMIN"})
        void generation_progress_admits_developer_reviewer_lead_and_admin(AppRole role) throws Exception {
            org.mockito.Mockito.when(axRenewalService.implementationProgress(
                            org.mockito.ArgumentMatchers.any(),
                            org.mockito.ArgumentMatchers.any(),
                            org.mockito.ArgumentMatchers.any()))
                    .thenReturn(java.util.Optional.empty());

            mvc.perform(get("/api/v1/ax-renewal/projects/" + UUID.randomUUID() + "/implementation/"
                                    + UUID.randomUUID() + "/progress")
                            .with(as(role)))
                    .andExpect(status().isOk());
        }

        @Test
        void generation_progress_refuses_a_caller_with_no_session() throws Exception {
            mvc.perform(get("/api/v1/ax-renewal/projects/" + UUID.randomUUID() + "/implementation/"
                            + UUID.randomUUID() + "/progress"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        void a_local_run_s_status_admits_an_admin() throws Exception {
            org.mockito.Mockito.when(axRenewalService.runLocallyStatus(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                    .thenReturn(new com.coc.docs.axrenewal.AxRenewalLocalRunner.Status(
                            com.coc.docs.axrenewal.AxRenewalLocalRunner.Status.STATE_STOPPED, List.of(), null, null));

            mvc.perform(get("/api/v1/ax-renewal/projects/" + UUID.randomUUID() + "/run-locally/status")
                            .with(as(AppRole.ADMIN)))
                    .andExpect(status().isOk());
        }

        @Test
        void explaining_a_local_run_failure_refuses_a_plain_user() throws Exception {
            // AI Error Assistant, phase 1 (ADR-062) — read-only, but it can
            // still point a local model at the destination to read it, so it
            // sits at the same floor as assistant/chat above, not the plain
            // read floor run-locally/status sits at just above.
            mvc.perform(post("/api/v1/ax-renewal/projects/" + UUID.randomUUID() + "/run-locally/explain")
                            .with(as(AppRole.USER)))
                    .andExpect(status().isForbidden());
        }

        @Test
        void explaining_a_local_run_failure_admits_an_admin() throws Exception {
            org.mockito.Mockito.doThrow(ApiException.notFound("project"))
                    .when(axRenewalService)
                    .explainRunLocallyFailure(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());

            // 404 from the stub, not 403 — the route was reached.
            mvc.perform(post("/api/v1/ax-renewal/projects/" + UUID.randomUUID() + "/run-locally/explain")
                            .with(as(AppRole.ADMIN)))
                    .andExpect(status().isNotFound());
        }

        // --- AI Error Assistant / UI Customization, Phase 2 (ADR-062) ---
        // Same floor as start/approve/reject/cancel above (they all execute
        // real code on the backend's own machine or write to the real
        // destination): Admin or exactly Developer (ADR-093). Developer's
        // admission is a_developer_reaches_every_write_action; these show
        // the refusal side.

        @ParameterizedTest
        @EnumSource(value = AppRole.class, names = {"USER", "REVIEWER", "LEAD"})
        void starting_an_error_fix_refuses_user_reviewer_and_lead(AppRole role) throws Exception {
            mvc.perform(post("/api/v1/ax-renewal/projects/" + UUID.randomUUID() + "/implementation/error-fix/start")
                            .with(as(role)))
                    .andExpect(status().isForbidden());
        }

        @ParameterizedTest
        @EnumSource(value = AppRole.class, names = {"USER", "REVIEWER", "LEAD"})
        void starting_a_ui_change_refuses_user_reviewer_and_lead(AppRole role) throws Exception {
            mvc.perform(post("/api/v1/ax-renewal/projects/" + UUID.randomUUID() + "/implementation/ui-change/start")
                            .with(as(role)))
                    .andExpect(status().isForbidden());
        }

        @ParameterizedTest
        @EnumSource(value = AppRole.class, names = {"USER", "REVIEWER", "LEAD"})
        void running_tests_against_a_generate_run_refuses_user_reviewer_and_lead(AppRole role) throws Exception {
            mvc.perform(post("/api/v1/ax-renewal/projects/" + UUID.randomUUID() + "/implementation/"
                            + UUID.randomUUID() + "/run-tests")
                            .with(as(role)))
                    .andExpect(status().isForbidden());
        }

        @ParameterizedTest
        @EnumSource(value = AppRole.class, names = {"USER", "REVIEWER", "LEAD"})
        void reverting_an_applied_run_refuses_user_reviewer_and_lead(AppRole role) throws Exception {
            mvc.perform(post("/api/v1/ax-renewal/projects/" + UUID.randomUUID() + "/implementation/"
                            + UUID.randomUUID() + "/revert")
                            .with(as(role)))
                    .andExpect(status().isForbidden());
        }

        @Test
        void confirming_a_section_refuses_a_plain_user() throws Exception {
            // Pins the baseline floor down explicitly alongside the
            // Developer-specific case above (ADR-093).
            mvc.perform(post("/api/v1/ax-renewal/projects/" + UUID.randomUUID() + "/plan/stack_move/confirm")
                            .with(as(AppRole.USER)))
                    .andExpect(status().isForbidden());
        }
    }
}
