package com.coc.docs.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import com.coc.docs.page.PageService;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
@Import({SecurityConfig.class, ProblemResponses.class, SecurityRulesTest.Beans.class})
class SecurityRulesTest {

    @Autowired MockMvc mvc;

    // Stubbed because these tests are about who may reach a route, not what it
    // returns. A rule that admitted the wrong caller would be caught here even
    // if every service threw.
    @MockitoBean PageService pageService;
    @MockitoBean AccountService accountService;
    @MockitoBean SessionService sessionService;
    @MockitoBean BlockBodyValidator validator;

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
                    new AppProperties.Storage("", "test", "us-east-1"));
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
    @DisplayName("account management")
    class AccountManagement {

        @Test
        void refuses_an_admin() throws Exception {
            // FR-AUTH-008 in one assertion: an admin publishes content, and
            // granting roles is a different kind of authority. This is the one
            // route inside /admin/** that an admin cannot reach.
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
}
