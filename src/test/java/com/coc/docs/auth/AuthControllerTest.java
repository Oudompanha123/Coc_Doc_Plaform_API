package com.coc.docs.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
import com.coc.docs.web.ApiException;
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
import org.springframework.http.HttpStatus;
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
@Import({
    SecurityConfig.class,
    ProblemResponses.class,
    com.coc.docs.config.WebMvcConfig.class,
    MustChangePasswordInterceptor.class,
    AuthControllerTest.Beans.class
})
class AuthControllerTest {

    @Autowired MockMvc mvc;

    @MockitoBean SessionService sessions;
    @MockitoBean PasswordResetService passwordResets;
    @MockitoBean RegistrationOtpService registrationOtp;
    @MockitoBean TotpService totp;

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
                    new AppProperties.Storage("", "test", "us-east-1", "", ""));
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
        Mockito.reset(sessions, passwordResets, registrationOtp, totp);
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
     * ADR-021 (revision of ADR-014/017): registration is now three calls,
     * none of which is {@code accountService.register} directly any more —
     * that call moved inside {@code RegistrationOtpService.verify}, mocked
     * here like every other collaborator this controller talks to.
     */
    @Nested
    @DisplayName("POST /api/v1/auth/register")
    class Register {

        @Test
        @DisplayName("mints and emails a code rather than creating an account")
        void startsAChallengeInsteadOfAnAccount() throws Exception {
            RegistrationChallenge challenge = new RegistrationChallenge(
                    UUID.randomUUID(),
                    "new.person@example.com",
                    "New Person",
                    "bcrypt-hash",
                    new byte[] {1, 2, 3},
                    0,
                    0,
                    Instant.now(),
                    Instant.now().plus(RegistrationOtpService.TTL));
            when(registrationOtp.start(eq("new.person@example.com"), eq("New Person"), any()))
                    .thenReturn(challenge);

            mvc.perform(post("/api/v1/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"email":"new.person@example.com","displayName":"New Person","password":"hunter2222"}
                                    """))
                    // 202: accepted, and there still isn't even a pending
                    // application until the code is confirmed.
                    .andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.message").value(
                            "We've sent a 6-digit verification code to new.person@example.com. "
                                    + "It expires in 10 minutes."))
                    .andExpect(jsonPath("$.expiresInSeconds").value(600));

            verify(registrationOtp).start(eq("new.person@example.com"), eq("New Person"), any());
            verify(accountService, never()).register(any(), any(), any());
        }

        @Test
        @DisplayName("rejects a blank email without reaching the service")
        void rejectsBlankEmail() throws Exception {
            mvc.perform(post("/api/v1/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"email":"","displayName":"New Person","password":"hunter2222"}
                                    """))
                    .andExpect(status().isBadRequest());

            verify(registrationOtp, never()).start(any(), any(), any());
        }
    }

    @Nested
    @DisplayName("POST /api/v1/auth/register/resend")
    class ResendRegistrationCode {

        @Test
        @DisplayName("re-sends the code, same response shape as /register")
        void resends() throws Exception {
            RegistrationChallenge challenge = new RegistrationChallenge(
                    UUID.randomUUID(),
                    "new.person@example.com",
                    "New Person",
                    "bcrypt-hash",
                    new byte[] {1, 2, 3},
                    0,
                    1,
                    Instant.now(),
                    Instant.now().plus(RegistrationOtpService.TTL));
            when(registrationOtp.resend("new.person@example.com")).thenReturn(challenge);

            mvc.perform(post("/api/v1/auth/register/resend")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"email":"new.person@example.com"}
                                    """))
                    .andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.expiresInSeconds").value(600));

            verify(registrationOtp).resend("new.person@example.com");
        }

        @Test
        @DisplayName("reports a too-soon resend as 429, not a generic failure")
        void reportsCooldownPlainly() throws Exception {
            when(registrationOtp.resend(any()))
                    .thenThrow(new ApiException(
                            HttpStatus.TOO_MANY_REQUESTS,
                            "A code was just sent to this address. Please wait a moment before "
                                    + "requesting another."));

            mvc.perform(post("/api/v1/auth/register/resend")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"email":"new.person@example.com"}
                                    """))
                    .andExpect(status().isTooManyRequests());
        }

        @Test
        @DisplayName("rejects a blank email without reaching the service")
        void rejectsBlankEmail() throws Exception {
            mvc.perform(post("/api/v1/auth/register/resend")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"email":""}
                                    """))
                    .andExpect(status().isBadRequest());

            verify(registrationOtp, never()).resend(any());
        }
    }

    @Nested
    @DisplayName("POST /api/v1/auth/register/verify")
    class VerifyRegistration {

        @Test
        @DisplayName("creates the pending application on a confirmed code")
        void createsPendingApplication() throws Exception {
            Account created = new Account(
                    UUID.randomUUID(),
                    "new.person@example.com",
                    "New Person",
                    java.util.EnumSet.of(AppRole.GUEST),
                    null,
                    null,
                    AccountStatus.PENDING,
                    null,
                    Instant.now(),
                    Instant.now(),
                    false,
                    true);
            when(registrationOtp.verify("new.person@example.com", "123456")).thenReturn(created);

            mvc.perform(post("/api/v1/auth/register/verify")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"email":"new.person@example.com","code":"123456"}
                                    """))
                    // 201, not 202: unlike step 1, the account row now
                    // genuinely exists — still not usable until approved.
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.status").value("PENDING"))
                    .andExpect(jsonPath("$.message")
                            .value("Your email is verified and your registration has been received. "
                                    + "An administrator has to approve it before you can sign in."));
        }

        @Test
        @DisplayName("rejects a code that isn't 6 digits without reaching the service")
        void rejectsMalformedCode() throws Exception {
            mvc.perform(post("/api/v1/auth/register/verify")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"email":"new.person@example.com","code":"12"}
                                    """))
                    .andExpect(status().isBadRequest());

            verify(registrationOtp, never()).verify(any(), any());
        }

        /**
         * Unlike sign-in's genericism, a rejected code here is reported
         * plainly — see {@code RegistrationOtpService.verify}'s javadoc for
         * why: the caller is the one who supplied this exact email to
         * {@code /register} moments earlier, not a third party.
         */
        @Test
        @DisplayName("reports an incorrect code plainly, as 400")
        void reportsWrongCodePlainly() throws Exception {
            when(registrationOtp.verify(eq("new.person@example.com"), eq("000000")))
                    .thenThrow(ApiException.badRequest("That code is incorrect. Please try again."));

            mvc.perform(post("/api/v1/auth/register/verify")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"email":"new.person@example.com","code":"000000"}
                                    """))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.detail").value("That code is incorrect. Please try again."));
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

    /**
     * ADR-018. The controller's whole job here is to add nothing that would
     * let the caller tell "we emailed you" apart from "there was nothing to
     * email" — see {@code PasswordResetService.requestReset}'s javadoc for
     * where that distinction is actually made.
     */
    @Nested
    @DisplayName("POST /api/v1/auth/forgot-password")
    class ForgotPassword {

        @Test
        @DisplayName("always answers 200 with the same generic message")
        void alwaysGeneric() throws Exception {
            mvc.perform(post("/api/v1/auth/forgot-password")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"email":"anyone@example.com"}
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.message").value(
                            "If that email is registered, we've sent password reset instructions."));

            verify(passwordResets).requestReset("anyone@example.com");
        }

        @Test
        @DisplayName("rejects a blank email without reaching the service")
        void rejectsBlankEmail() throws Exception {
            mvc.perform(post("/api/v1/auth/forgot-password")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"email":""}
                                    """))
                    .andExpect(status().isBadRequest());

            verify(passwordResets, never()).requestReset(any());
        }
    }

    /**
     * ADR-018. Unlike {@code /forgot-password}, a rejected token here is
     * reported plainly — the caller already holds a value from their own
     * mailbox, so refusing it discloses nothing about anyone else's account.
     */
    @Nested
    @DisplayName("POST /api/v1/auth/reset-password")
    class ResetPassword {

        @Test
        @DisplayName("sets the new password and confirms")
        void resetsPassword() throws Exception {
            // ADR-029: the controller now relays whichever message the
            // service returns (it differs for a Google-only account's first
            // password vs. a genuine reset) rather than hardcoding one.
            when(passwordResets.resetPassword("good-token", "newHunter2222"))
                    .thenReturn("Your password has been reset. You can now log in.");

            mvc.perform(post("/api/v1/auth/reset-password")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"token":"good-token","newPassword":"newHunter2222"}
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.message").value(
                            "Your password has been reset. You can now log in."));

            verify(passwordResets).resetPassword("good-token", "newHunter2222");
        }

        @Test
        @DisplayName("relays the distinct message for a Google-only account's first password (ADR-029)")
        void relaysInitialSetMessage() throws Exception {
            when(passwordResets.resetPassword("google-only-token", "newHunter2222"))
                    .thenReturn("Your password has been set. You can now log in with your email and password.");

            mvc.perform(post("/api/v1/auth/reset-password")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"token":"google-only-token","newPassword":"newHunter2222"}
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.message").value(
                            "Your password has been set. You can now log in with your email and password."));
        }

        @Test
        @DisplayName("reports an invalid or expired token plainly, as 400")
        void invalidTokenIsReportedPlainly() throws Exception {
            doThrow(ApiException.badRequest("this reset link is invalid or has expired"))
                    .when(passwordResets)
                    .resetPassword(eq("bad-token"), any());

            mvc.perform(post("/api/v1/auth/reset-password")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"token":"bad-token","newPassword":"newHunter2222"}
                                    """))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.detail").value("this reset link is invalid or has expired"));
        }

        @Test
        @DisplayName("rejects a short password without reaching the service")
        void rejectsShortPassword() throws Exception {
            mvc.perform(post("/api/v1/auth/reset-password")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"token":"good-token","newPassword":"short"}
                                    """))
                    .andExpect(status().isBadRequest());

            verify(passwordResets, never()).resetPassword(any(), any());
        }
    }

    /**
     * Three callers, one route (ADR-022/ADR-024, revision of ADR-020/017;
     * ADR-030 adds the third): the first-login completion — the caller
     * already holds a session issued on the temporary password — the
     * voluntary "My Profile &rarr; Change Password" self-service screen, and
     * that same screen used by a Google-only account setting a password it
     * never had. {@code SessionService} is mocked, so these tests cover the
     * HTTP/DTO layer and the message branch only; the real current-password
     * rule — including that the initial-set caller skips it entirely — lives
     * in {@code ChangeOwnPasswordIntegrationTest}.
     */
    @Nested
    @DisplayName("POST /api/v1/auth/change-password")
    class ChangePassword {

        @Test
        @DisplayName("voluntary change: confirms with the 'changed' message, not the first-login one")
        void changesPasswordVoluntarily() throws Exception {
            mvc.perform(post("/api/v1/auth/change-password")
                            .with(as(AppRole.USER))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"currentPassword":"OldPassword123!","newPassword":"NewPassword123!","confirmPassword":"NewPassword123!"}
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.message").value("Your password has been changed."));

            verify(sessions).changeOwnPassword(any(), eq("OldPassword123!"), eq("NewPassword123!"));
        }

        @Test
        @DisplayName("forced first-login: confirms with the 'you can now access your account' message")
        void changesPasswordDuringForcedFirstLogin() throws Exception {
            mvc.perform(post("/api/v1/auth/change-password")
                            .with(SecurityMockMvcRequestPostProcessors.authentication(
                                    TestCallers.authentication(AppRole.USER, true)))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"currentPassword":"TempPassword123!","newPassword":"NewPassword123!","confirmPassword":"NewPassword123!"}
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.message").value(
                            "Password created successfully. You can now access your account."));

            verify(sessions).changeOwnPassword(any(), eq("TempPassword123!"), eq("NewPassword123!"));
        }

        @Test
        @DisplayName("forced first-login: a missing temporary password now reaches the session layer, which refuses it (ADR-030)")
        void missingCurrentPasswordDuringForcedFirstLoginReachesTheSessionLayer() throws Exception {
            // currentPassword is no longer @NotBlank at the DTO level (ADR-030)
            // — a Google-only caller genuinely has none to send. So a missing
            // value for THIS caller (who has a temporary password to prove) is
            // no longer stopped at the boundary; SessionService.changeOwnPassword
            // is what refuses it now. That real rejection is exercised against a
            // database in ChangeOwnPasswordIntegrationTest; here only the wiring
            // is asserted — the request reaches the (mocked) service layer.
            doThrow(ApiException.badRequest("your current password is incorrect"))
                    .when(sessions)
                    .changeOwnPassword(any(), any(), any());

            mvc.perform(post("/api/v1/auth/change-password")
                            .with(SecurityMockMvcRequestPostProcessors.authentication(
                                    TestCallers.authentication(AppRole.USER, true)))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"newPassword":"NewPassword123!","confirmPassword":"NewPassword123!"}
                                    """))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.detail").value("your current password is incorrect"));

            verify(sessions).changeOwnPassword(any(), isNull(), eq("NewPassword123!"));
        }

        @Test
        @DisplayName("Google-only account: sets an initial password with the 'set' message, sending no currentPassword (ADR-030)")
        void setsInitialPasswordForGoogleOnlyAccount() throws Exception {
            mvc.perform(post("/api/v1/auth/change-password")
                            .with(SecurityMockMvcRequestPostProcessors.authentication(
                                    TestCallers.authentication(AppRole.USER, false, false)))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"newPassword":"NewPassword123!","confirmPassword":"NewPassword123!"}
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.message").value(
                            "Your password has been set. You can now also sign in with your email and password."));

            verify(sessions).changeOwnPassword(any(), isNull(), eq("NewPassword123!"));
        }

        @Test
        @DisplayName("rejects mismatched confirmation without reaching the session layer")
        void rejectsMismatch() throws Exception {
            mvc.perform(post("/api/v1/auth/change-password")
                            .with(as(AppRole.USER))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"currentPassword":"OldPassword123!","newPassword":"NewPassword123!","confirmPassword":"Different123!"}
                                    """))
                    .andExpect(status().isBadRequest());

            verify(sessions, never()).changeOwnPassword(any(), any(), any());
        }

        @Test
        @DisplayName("rejects a short password without reaching the session layer")
        void rejectsShortPassword() throws Exception {
            mvc.perform(post("/api/v1/auth/change-password")
                            .with(as(AppRole.USER))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"currentPassword":"OldPassword123!","newPassword":"short","confirmPassword":"short"}
                                    """))
                    .andExpect(status().isBadRequest());

            verify(sessions, never()).changeOwnPassword(any(), any(), any());
        }

        @Test
        @DisplayName("refuses an anonymous caller")
        void refusesAnonymous() throws Exception {
            mvc.perform(post("/api/v1/auth/change-password")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"currentPassword":"OldPassword123!","newPassword":"NewPassword123!","confirmPassword":"NewPassword123!"}
                                    """))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("reports reusing the current password plainly, as 400")
        void rejectsCurrentPasswordReuse() throws Exception {
            doThrow(ApiException.badRequest("choose a password different from your current one"))
                    .when(sessions)
                    .changeOwnPassword(any(), any(), any());

            mvc.perform(post("/api/v1/auth/change-password")
                            .with(as(AppRole.USER))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"currentPassword":"SamePassword123!","newPassword":"SamePassword123!","confirmPassword":"SamePassword123!"}
                                    """))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.detail").value(
                            "choose a password different from your current one"));
        }

        @Test
        @DisplayName("reports an incorrect current password plainly, as 400 (ADR-022)")
        void rejectsWrongCurrentPassword() throws Exception {
            doThrow(ApiException.badRequest("your current password is incorrect"))
                    .when(sessions)
                    .changeOwnPassword(any(), any(), any());

            mvc.perform(post("/api/v1/auth/change-password")
                            .with(as(AppRole.USER))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"currentPassword":"WrongPassword123!","newPassword":"NewPassword123!","confirmPassword":"NewPassword123!"}
                                    """))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.detail").value("your current password is incorrect"));
        }
    }

    /**
     * ADR-026. {@link SessionService} is mocked, so these cover the HTTP/DTO
     * layer only — validation and storage belong to {@code S3AvatarStorageTest}.
     */
    @Nested
    @DisplayName("POST /api/v1/auth/me/avatar")
    class UploadAvatar {

        @Test
        @DisplayName("uploads and returns the caller's updated profile")
        void uploadsAndReturnsUpdatedProfile() throws Exception {
            var caller = TestCallers.user(AppRole.USER);
            Account updated = caller.account().withUploadedAvatar(
                    "https://s3.test/bucket/avatars/" + caller.id() + "/photo.png", Instant.now());
            when(sessions.uploadOwnAvatar(any(), any())).thenReturn(updated);

            org.springframework.mock.web.MockMultipartFile file =
                    new org.springframework.mock.web.MockMultipartFile(
                            "file", "photo.png", "image/png", new byte[] {1, 2, 3});

            mvc.perform(multipart("/api/v1/auth/me/avatar").file(file).with(as(AppRole.USER)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.avatarUrl").value(updated.avatarUrl()));
        }

        @Test
        @DisplayName("reports a rejected file plainly, as 400")
        void reportsRejectedFilePlainly() throws Exception {
            when(sessions.uploadOwnAvatar(any(), any()))
                    .thenThrow(ApiException.badRequest("photos must be PNG, JPEG, or WebP"));

            org.springframework.mock.web.MockMultipartFile file =
                    new org.springframework.mock.web.MockMultipartFile(
                            "file", "photo.txt", "text/plain", new byte[] {1, 2, 3});

            mvc.perform(multipart("/api/v1/auth/me/avatar").file(file).with(as(AppRole.USER)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.detail").value("photos must be PNG, JPEG, or WebP"));
        }

        @Test
        @DisplayName("refuses an anonymous caller")
        void refusesAnonymous() throws Exception {
            org.springframework.mock.web.MockMultipartFile file =
                    new org.springframework.mock.web.MockMultipartFile(
                            "file", "photo.png", "image/png", new byte[] {1, 2, 3});

            mvc.perform(multipart("/api/v1/auth/me/avatar").file(file))
                    .andExpect(status().isUnauthorized());

            verify(sessions, never()).uploadOwnAvatar(any(), any());
        }
    }

    /** ADR-026. */
    @Nested
    @DisplayName("DELETE /api/v1/auth/me/avatar")
    class RemoveAvatar {

        @Test
        @DisplayName("removes the uploaded photo and returns the caller's updated profile")
        void removesAndReturnsUpdatedProfile() throws Exception {
            var caller = TestCallers.user(AppRole.USER);
            when(sessions.removeOwnAvatar(any())).thenReturn(caller.account());

            mvc.perform(delete("/api/v1/auth/me/avatar").with(as(AppRole.USER)))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("reports there being nothing to remove plainly, as 400")
        void reportsNothingToRemovePlainly() throws Exception {
            when(sessions.removeOwnAvatar(any()))
                    .thenThrow(ApiException.badRequest("there is no uploaded photo to remove"));

            mvc.perform(delete("/api/v1/auth/me/avatar").with(as(AppRole.USER)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.detail").value("there is no uploaded photo to remove"));
        }

        @Test
        @DisplayName("refuses an anonymous caller")
        void refusesAnonymous() throws Exception {
            mvc.perform(delete("/api/v1/auth/me/avatar"))
                    .andExpect(status().isUnauthorized());

            verify(sessions, never()).removeOwnAvatar(any());
        }
    }

    /**
     * {@code MustChangePasswordInterceptor}, exercised through this
     * controller's own routes rather than a synthetic one, since it is what
     * an invited account actually hits on its first request after logging in
     * with the temporary password.
     */
    @Nested
    @DisplayName("the first-login gate (must-change-password)")
    class MustChangePasswordGate {

        private static RequestPostProcessor mustChangePassword() {
            return SecurityMockMvcRequestPostProcessors.authentication(
                    TestCallers.authentication(AppRole.USER, true));
        }

        @Test
        @DisplayName("blocks PATCH /auth/me for a caller who must still change their password")
        void blocksProfileUpdate() throws Exception {
            mvc.perform(patch("/api/v1/auth/me")
                            .with(mustChangePassword())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"displayName":"New Name"}
                                    """))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("does not block GET /auth/me — the frontend needs it to learn mustChangePassword")
        void doesNotBlockMe() throws Exception {
            mvc.perform(get("/api/v1/auth/me").with(mustChangePassword()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.mustChangePassword").value(true));
        }

        @Test
        @DisplayName("does not block POST /auth/change-password — it is the way out")
        void doesNotBlockChangePassword() throws Exception {
            mvc.perform(post("/api/v1/auth/change-password")
                            .with(mustChangePassword())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"currentPassword":"TempPassword123!","newPassword":"NewPassword123!","confirmPassword":"NewPassword123!"}
                                    """))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("does not block POST /auth/logout")
        void doesNotBlockLogout() throws Exception {
            mvc.perform(post("/api/v1/auth/logout").with(mustChangePassword()))
                    .andExpect(status().isNoContent());
        }
    }

    private static String passwordLoginBody() {
        return """
                {"email":"user@example.com","password":"Password123!"}""";
    }

    /**
     * ADR-028. The controller has two jobs here: pass through an ordinary
     * session unchanged, and — for an account with confirmed TOTP — issue a
     * pending-MFA cookie instead of a session, never both at once.
     */
    @Nested
    @DisplayName("POST /api/v1/auth/login-password")
    class LoginPassword {

        @Test
        @DisplayName("issues a session when the account has no TOTP enrolled")
        void issuesSessionWithoutMfa() throws Exception {
            var caller = TestCallers.user(AppRole.USER);
            when(sessions.signInWithPassword(any(), any(), any(), any()))
                    .thenReturn(new SessionService.IssuedSession(
                            "opaque-session-token", caller.account(), Instant.now()));

            mvc.perform(post("/api/v1/auth/login-password")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(passwordLoginBody()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.authenticated").value(true))
                    .andExpect(jsonPath("$.email").value(caller.email()));
        }

        @Test
        @DisplayName("returns mfaRequired and a pending-MFA cookie instead, when TOTP is enrolled")
        void returnsMfaRequiredInstead() throws Exception {
            when(sessions.signInWithPassword(any(), any(), any(), any()))
                    .thenReturn(new SessionService.MfaRequired("pending-token", Instant.now().plusSeconds(300)));

            MvcResult result = mvc.perform(post("/api/v1/auth/login-password")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(passwordLoginBody()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.mfaRequired").value(true))
                    .andReturn();

            assertThat(result.getResponse().getHeaders(HttpHeaders.SET_COOKIE))
                    .anySatisfy(header -> assertThat(header)
                            .contains(TotpService.MFA_PENDING_COOKIE_NAME + "=pending-token")
                            .contains("HttpOnly")
                            .contains("SameSite=Lax"))
                    // No real session cookie alongside it -- the whole point of
                    // this branch is that a session is not yet issued.
                    .noneMatch(header -> header.startsWith(SessionService.COOKIE_NAME + "="));
        }
    }

    /**
     * ADR-028. Redeems the pending-MFA cookie {@code /login-password} left
     * behind; {@code TotpService}/{@code SessionService} are both mocked, so
     * this only proves the controller's own plumbing (cookie in, session
     * cookie + cleared pending cookie out) — code verification itself is
     * {@code TotpServiceIntegrationTest}'s job.
     */
    @Nested
    @DisplayName("POST /api/v1/auth/mfa/verify")
    class MfaVerify {

        @Test
        @DisplayName("completes sign-in and clears the pending cookie on a correct code")
        void completesSignIn() throws Exception {
            var caller = TestCallers.user(AppRole.USER);
            when(totp.verifyLoginChallenge(eq("pending-token"), eq("123456"))).thenReturn(caller.account());
            when(sessions.issueForAccount(eq(caller.account()), any(), any()))
                    .thenReturn(new SessionService.IssuedSession(
                            "opaque-session-token", caller.account(), Instant.now()));

            MvcResult result = mvc.perform(post("/api/v1/auth/mfa/verify")
                            .cookie(new jakarta.servlet.http.Cookie(
                                    TotpService.MFA_PENDING_COOKIE_NAME, "pending-token"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"code":"123456"}"""))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.authenticated").value(true))
                    .andReturn();

            assertThat(result.getResponse().getHeaders(HttpHeaders.SET_COOKIE))
                    .anySatisfy(header -> assertThat(header)
                            .contains(SessionService.COOKIE_NAME + "=opaque-session-token"))
                    .anySatisfy(header -> assertThat(header)
                            .contains(TotpService.MFA_PENDING_COOKIE_NAME + "=")
                            .contains("Max-Age=0"));
        }

        @Test
        @DisplayName("refuses without a pending-MFA cookie, and never reaches TotpService")
        void refusesWithoutPendingCookie() throws Exception {
            mvc.perform(post("/api/v1/auth/mfa/verify")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"code":"123456"}"""))
                    .andExpect(status().isBadRequest());

            verify(totp, never()).verifyLoginChallenge(any(), any());
        }

        @Test
        @DisplayName("a wrong code is refused")
        void refusesWrongCode() throws Exception {
            when(totp.verifyLoginChallenge(any(), any()))
                    .thenThrow(ApiException.badRequest("That code is incorrect."));

            mvc.perform(post("/api/v1/auth/mfa/verify")
                            .cookie(new jakarta.servlet.http.Cookie(
                                    TotpService.MFA_PENDING_COOKIE_NAME, "pending-token"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"code":"000000"}"""))
                    .andExpect(status().isBadRequest());
        }
    }
}
