package com.coc.docs.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.coc.docs.account.Account;
import com.coc.docs.account.AccountService;
import com.coc.docs.account.AccountStatus;
import com.coc.docs.account.AppRole;
import com.coc.docs.config.AppProperties;
import com.coc.docs.config.ProblemResponses;
import com.coc.docs.config.SecurityConfig;
import com.coc.docs.content.BlockBodyValidator;
import com.coc.docs.page.PageService;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Tests the HTTP behaviour of {@code com.coc.docs.web.AuthController}.
 *
 * <p>The sign-in routes had no test of their own. They are the routes most
 * likely to be edited under time pressure and the ones where a mistake is
 * least visible, since a session that is issued too generously still looks
 * like a working sign-in.
 *
 * <p>No database and no Docker, matching {@code SecurityRulesTest}: a test that
 * only ran where Docker was installed would be skipped on the machine where
 * someone is changing the cookie.
 *
 * <p>{@link SessionService} is mocked deliberately. What is under test is the
 * translation between HTTP and the session layer — cookie attributes, status
 * codes, and what an absent caller produces. Whether a Google token is genuine
 * belongs to {@link GoogleIdTokenVerifier} and is not re-litigated here.
 *
 * <p>// req: FR-AUTH-001, FR-AUTH-002, FR-AUTH-005, FR-AUTH-007, NFR-SEC-SESSION,
 * ADR-011, TM-002
 */
@WebMvcTest(controllers = com.coc.docs.web.AuthController.class)
@Import({SecurityConfig.class, ProblemResponses.class, AuthControllerTest.Beans.class})
class AuthControllerTest {

    @Autowired MockMvc mvc;

    @MockitoBean SessionService sessions;

    // Present so the context and the filter chain wire up; untouched here.
    @MockitoBean PageService pageService;
    @MockitoBean AccountService accountService;
    @MockitoBean BlockBodyValidator validator;

    // AdminAccountController reads last sign-in times from it, so the context
    // needs one to build. Untouched by these tests.
    @MockitoBean com.coc.docs.auth.SessionRepository sessionRepository;

    @TestConfiguration
    static class Beans {

        /** The real one: it is pure SecureRandom, and a stub would make the
         *  cookie-versus-body test below prove nothing. */
        @Bean
        NonceService nonceService() {
            return new NonceService();
        }

        /** Real bcrypt, not a stub: these tests exercise the filter chain, and a
         *  fake encoder would let a password path pass that production refuses. */
        @Bean
        PasswordEncoder passwordEncoder() {
            return new BCryptPasswordEncoder();
        }

        /** cookieSecure false, as in local development over plain HTTP. */
        @Bean
        AppProperties appProperties() {
            return new AppProperties(
                    Duration.ofDays(1),
                    "http://localhost:3000",
                    false,
                    "test-client-id",
                    new AppProperties.Storage("", "test", "us-east-1"));
        }

        @Bean
        SessionAuthenticationFilter sessionAuthenticationFilter(SessionService sessions) {
            return new SessionAuthenticationFilter(sessions);
        }
    }

    /**
     * The mock is shared across the whole class, and the {@code @Nested} classes
     * here run in an order JUnit does not promise. Without this, a stub or an
     * invocation from one method is still present in the next, and a
     * {@code verify(never())} fails for a call another test made. Reset makes
     * each method stand on its own.
     */
    @BeforeEach
    void resetSessionService() {
        Mockito.reset(sessions);
    }

    private static RequestPostProcessor as(AppRole role) {
        return SecurityMockMvcRequestPostProcessors.authentication(
                TestCallers.authentication(role));
    }

    private static String body(String idToken) {
        return "{\"idToken\":\"" + idToken + "\"}";
    }

    @Nested
    @DisplayName("POST /api/v1/auth/google")
    class SignIn {

        @Test
        @DisplayName("issues a session and reports the caller as authenticated")
        void issuesSession() throws Exception {
            var caller = TestCallers.user(AppRole.ADMIN);
            when(sessions.signIn(eq("good-token"), any(), any(), any()))
                    .thenReturn(new SessionService.IssuedSession(
                            "opaque-session-token",
                            caller.account(),
                            Instant.parse("2026-09-10T09:00:00Z")));

            mvc.perform(post("/api/v1/auth/google")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body("good-token")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.email").value(caller.email()))
                    .andExpect(jsonPath("$.role").value("admin"))
                    .andExpect(jsonPath("$.authenticated").value(true));
        }

        /**
         * The attributes are the security properties, not decoration. HttpOnly is
         * what an XSS bug cannot get past, and a wrong Path or SameSite is the
         * class of defect a happy-path test still passes over.
         */
        @Test
        @DisplayName("sets an HttpOnly, Lax, root-path session cookie")
        void setsHardenedCookie() throws Exception {
            var caller = TestCallers.user(AppRole.USER);
            when(sessions.signIn(any(), any(), any(), any()))
                    .thenReturn(new SessionService.IssuedSession(
                            "opaque-session-token", caller.account(), Instant.now()));

            MvcResult result = mvc.perform(post("/api/v1/auth/google")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body("good-token")))
                    .andExpect(status().isOk())
                    .andReturn();

            assertThat(result.getResponse().getHeader(HttpHeaders.SET_COOKIE))
                    .contains(SessionService.COOKIE_NAME + "=opaque-session-token")
                    .contains("HttpOnly")
                    .contains("SameSite=Lax")
                    .contains("Path=/")
                    // cookieSecure is false in this configuration. Asserted so
                    // that changing the property stays a visible decision.
                    .doesNotContain("Secure");
        }

        @Test
        @DisplayName("rejects a blank token without reaching the session layer")
        void rejectsBlankToken() throws Exception {
            mvc.perform(post("/api/v1/auth/google")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body("")))
                    .andExpect(status().isBadRequest());

            verify(sessions, never()).signIn(any(), any(), any(), any());
        }

        /**
         * One fixed 401 whatever the real reason was — here a deactivated
         * account. The reason goes to the audit trail; telling the caller which
         * check failed is free reconnaissance (UC-004 E-3).
         */
        @Test
        @DisplayName("answers a generic 401 when sign-in is refused")
        void genericUnauthorized() throws Exception {
            when(sessions.signIn(any(), any(), any(), any()))
                    .thenThrow(new GoogleIdTokenVerifier.AuthenticationFailure(
                            "this account is deactivated"));

            mvc.perform(post("/api/v1/auth/google")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body("bad-token")))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.detail").value("sign-in could not be completed"))
                    // The reason must not travel. If this ever fails, the leak is
                    // the bug, not this assertion.
                    .andExpect(jsonPath("$.detail").value(
                            org.hamcrest.Matchers.not(
                                    org.hamcrest.Matchers.containsString("deactivated"))));
        }
    }

    @Nested
    @DisplayName("GET /api/v1/auth/me")
    class Me {

        /**
         * 200 with {@code authenticated: false}, not 401. This is the call the
         * frontend makes on every page load, and a visitor who is not signed in
         * is the expected answer rather than an error.
         */
        @Test
        @DisplayName("200 and authenticated:false with no session")
        void anonymous() throws Exception {
            mvc.perform(get("/api/v1/auth/me"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.authenticated").value(false))
                    .andExpect(jsonPath("$.id").doesNotExist())
                    // No identity may leak into the anonymous answer.
                    .andExpect(jsonPath("$.email").doesNotExist());
        }

        @Test
        @DisplayName("returns the caller's own account and role")
        void authenticated() throws Exception {
            mvc.perform(get("/api/v1/auth/me").with(as(AppRole.SUPER_ADMIN)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.authenticated").value(true))
                    .andExpect(jsonPath("$.role").value("super_admin"));
        }
    }

    @Nested
    @DisplayName("POST /api/v1/auth/logout")
    class SignOut {

        @Test
        @DisplayName("revokes the session server-side and clears the cookie")
        void revokesAndClears() throws Exception {
            MvcResult result = mvc.perform(post("/api/v1/auth/logout").with(as(AppRole.USER)))
                    .andExpect(status().isNoContent())
                    .andReturn();

            // The server-side revocation is the point of FR-AUTH-007. Clearing
            // the cookie alone would leave a credential that still validates for
            // anyone who captured it.
            verify(sessions).signOut(any());

            assertThat(result.getResponse().getHeader(HttpHeaders.SET_COOKIE))
                    .contains(SessionService.COOKIE_NAME + "=")
                    .contains("Max-Age=0")
                    // Must match the cookie that was set, or the browser keeps
                    // the original and sign-out silently does nothing.
                    .contains("Path=/")
                    .contains("SameSite=Lax");
        }

        /**
         * Idempotent on purpose: a 401 would leave a browser holding a dead
         * cookie it was refused permission to discard.
         */
        @Test
        @DisplayName("204 with no session, without calling signOut")
        void idempotentWithoutSession() throws Exception {
            mvc.perform(post("/api/v1/auth/logout")).andExpect(status().isNoContent());

            verify(sessions, never()).signOut(any());
        }
    }

    /**
     * As of the 2026-09-11 revision of ADR-014, a self-registration is
     * created {@code active} immediately — no {@code super_admin} approval
     * gate. This replaces the {@code PENDING}/"awaiting approval" behaviour
     * these tests would otherwise have covered.
     */
    @Nested
    @DisplayName("POST /api/v1/auth/register")
    class Register {

        @Test
        @DisplayName("creates the account active and reports that it can sign in immediately")
        void createsActiveAccount() throws Exception {
            Account created = new Account(
                    UUID.randomUUID(),
                    "new.person@example.com",
                    "New Person",
                    java.util.EnumSet.of(AppRole.USER),
                    null,
                    AccountStatus.ACTIVE,
                    null,
                    Instant.now(),
                    Instant.now());
            when(accountService.register(eq("new.person@example.com"), eq("New Person"), any()))
                    .thenReturn(created);

            mvc.perform(post("/api/v1/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"email":"new.person@example.com","displayName":"New Person","password":"hunter2222"}
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("ACTIVE"))
                    .andExpect(jsonPath("$.message").value("Your account has been created. You can now log in."));
        }
    }

    @Nested
    @DisplayName("POST /api/v1/auth/nonce")
    class Nonce {

        @Test
        @DisplayName("returns a nonce and sets it as a hardened, short-lived cookie")
        void issuesNonceCookie() throws Exception {
            MvcResult result = mvc.perform(post("/api/v1/auth/nonce"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.nonce").isNotEmpty())
                    .andReturn();

            assertThat(result.getResponse().getHeader(HttpHeaders.SET_COOKIE))
                    .contains(NonceService.COOKIE_NAME + "=")
                    // HttpOnly is the control, not a hardening detail: a nonce
                    // script can read is a nonce an XSS can satisfy.
                    .contains("HttpOnly")
                    .contains("SameSite=Lax")
                    .contains("Path=/")
                    .contains("Max-Age=" + NonceService.TTL.toSeconds());
        }

        @Test
        @DisplayName("issues a different nonce each time")
        void noncesAreNotReused() throws Exception {
            String first = mvc.perform(post("/api/v1/auth/nonce"))
                    .andReturn()
                    .getResponse()
                    .getContentAsString();
            String second = mvc.perform(post("/api/v1/auth/nonce"))
                    .andReturn()
                    .getResponse()
                    .getContentAsString();

            assertThat(first).isNotEqualTo(second);
        }

        /**
         * The point of the whole mechanism. The expected nonce must come from
         * the cookie, because a value carried in the request body is chosen by
         * the caller — a replayer would send the captured token together with
         * the nonce baked into it, and the two would agree.
         */
        @Test
        @DisplayName("sign-in verifies against the cookie, not the request body")
        void signInReadsTheCookieNotTheBody() throws Exception {
            var caller = TestCallers.user(AppRole.USER);
            when(sessions.signIn(any(), any(), any(), any()))
                    .thenReturn(new SessionService.IssuedSession(
                            "opaque-session-token", caller.account(), Instant.now()));

            mvc.perform(post("/api/v1/auth/google")
                            .cookie(new jakarta.servlet.http.Cookie(
                                    NonceService.COOKIE_NAME, "nonce-from-cookie"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"idToken":"good-token","nonce":"nonce-from-body"}"""))
                    .andExpect(status().isOk());

            verify(sessions).signIn(eq("good-token"), eq("nonce-from-cookie"), any(), any());
        }

        @Test
        @DisplayName("passes null when no nonce cookie is present, rather than inventing one")
        void missingCookieIsNull() throws Exception {
            var caller = TestCallers.user(AppRole.USER);
            when(sessions.signIn(any(), any(), any(), any()))
                    .thenReturn(new SessionService.IssuedSession(
                            "opaque-session-token", caller.account(), Instant.now()));

            mvc.perform(post("/api/v1/auth/google")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body("good-token")))
                    .andExpect(status().isOk());

            // Null, so KeycloakIdTokenService refuses with missing_nonce. A
            // blank string would be equally refused today, but only because
            // that check happens to test isBlank as well.
            verify(sessions).signIn(eq("good-token"), eq(null), any(), any());
        }

        @Test
        @DisplayName("spends the nonce cookie on a successful sign-in")
        void clearsNonceAfterUse() throws Exception {
            var caller = TestCallers.user(AppRole.USER);
            when(sessions.signIn(any(), any(), any(), any()))
                    .thenReturn(new SessionService.IssuedSession(
                            "opaque-session-token", caller.account(), Instant.now()));

            MvcResult result = mvc.perform(post("/api/v1/auth/google")
                            .cookie(new jakarta.servlet.http.Cookie(
                                    NonceService.COOKIE_NAME, "spent"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body("good-token")))
                    .andExpect(status().isOk())
                    .andReturn();

            // Left in place, a captured ID token stays replayable for the rest
            // of the nonce's five minutes.
            assertThat(result.getResponse().getHeaders(HttpHeaders.SET_COOKIE))
                    .anySatisfy(header -> assertThat(header)
                            .contains(NonceService.COOKIE_NAME + "=")
                            .contains("Max-Age=0"));
        }
    }
}
