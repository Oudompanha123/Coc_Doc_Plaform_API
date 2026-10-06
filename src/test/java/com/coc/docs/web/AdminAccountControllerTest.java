package com.coc.docs.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.coc.docs.account.Account;
import com.coc.docs.account.AccountService;
import com.coc.docs.account.AccountStatus;
import com.coc.docs.account.AppRole;
import com.coc.docs.auth.SessionRepository;
import com.coc.docs.auth.TestCallers;
import com.coc.docs.config.ProblemResponses;
import com.coc.docs.config.SecurityConfig;
import com.coc.docs.content.BlockBodyValidator;
import com.coc.docs.page.PageService;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.context.annotation.Import;

/**
 * The account administration API, which the Users &amp; Roles screen is built
 * on.
 *
 * <p>Whether the {@code super_admin} floor holds is {@code SecurityRulesTest}'s
 * job and is not repeated here. What this covers is the behaviour the screen
 * depends on: that the list carries a last sign-in, that creating an account
 * yields a usable row, and that the two ways a create can be refused are
 * refused distinguishably — an unknown role is the caller's mistake, a
 * duplicate address is a fact about the world.
 *
 * <p>// req: FR-AUTH-003, FR-AUTH-005, FR-AUTH-008, UC-004
 */
@WebMvcTest(controllers = com.coc.docs.web.AdminAccountController.class)
@Import({SecurityConfig.class, ProblemResponses.class, AdminAccountControllerTest.Beans.class})
class AdminAccountControllerTest {

    @Autowired MockMvc mvc;

    @MockitoBean AccountService accountService;
    @MockitoBean SessionRepository sessionRepository;
    @MockitoBean com.coc.docs.auth.SessionService sessionService;
    @MockitoBean com.coc.docs.auth.NonceService nonceService;
    @MockitoBean PageService pageService;
    @MockitoBean BlockBodyValidator validator;

    @org.springframework.boot.test.context.TestConfiguration
    static class Beans {

        /** cookieSecure false and a fixed client id, as in local development. */
        @org.springframework.context.annotation.Bean
        com.coc.docs.config.AppProperties appProperties() {
            return new com.coc.docs.config.AppProperties(
                    java.time.Duration.ofDays(1),
                    "http://localhost:3000",
                    false,
                    "test-client-id",
                    "test-client-secret",
                    new com.coc.docs.config.AppProperties.Storage("", "test", "us-east-1", "", ""));
        }

        /** Real bcrypt: the filter chain is under test, and a fake encoder
         *  would let a path pass that production refuses. */
        @org.springframework.context.annotation.Bean
        org.springframework.security.crypto.password.PasswordEncoder passwordEncoder() {
            return new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder();
        }

        @org.springframework.context.annotation.Bean
        com.coc.docs.auth.SessionAuthenticationFilter sessionAuthenticationFilter(
                com.coc.docs.auth.SessionService sessions) {
            return new com.coc.docs.auth.SessionAuthenticationFilter(sessions);
        }
    }

    private static final UUID ACCOUNT_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    private static Account account(String email, AppRole role) {
        return new Account(
                ACCOUNT_ID,
                email,
                "Person",
                EnumSet.of(role),
                null,
                null,
                AccountStatus.ACTIVE,
                null,
                null,
                Instant.parse("2026-09-01T09:00:00Z"),
                Instant.parse("2026-09-01T09:00:00Z"),
                false,
                true);
    }

    /** A super admin, the only role that reaches any of this. */
    private static RequestPostProcessor superAdmin() {
        return SecurityMockMvcRequestPostProcessors.authentication(
                TestCallers.authentication(AppRole.SUPER_ADMIN));
    }

    @Nested
    @DisplayName("GET /api/v1/admin/accounts")
    class ListAccounts {

        @Test
        @DisplayName("reports when each account last signed in")
        void includesLastSignIn() throws Exception {
            when(accountService.list()).thenReturn(List.of(account("person@example.com", AppRole.ADMIN)));
            when(sessionRepository.lastSignInByAccount())
                    .thenReturn(Map.of(ACCOUNT_ID, Instant.parse("2026-09-11T08:30:00Z")));

            mvc.perform(get("/api/v1/admin/accounts").with(superAdmin()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].email").value("person@example.com"))
                    .andExpect(jsonPath("$[0].role").value("admin"))
                    .andExpect(jsonPath("$[0].active").value(true))
                    .andExpect(jsonPath("$[0].lastSignInAt").value("2026-09-11T08:30:00Z"));
        }

        @Test
        @DisplayName("omits the last sign-in for someone who has never signed in")
        void absentWhenNeverSignedIn() throws Exception {
            when(accountService.list()).thenReturn(List.of(account("new@example.com", AppRole.USER)));
            // No entry for this account: they have never signed in, or their
            // last session has been purged. The screen renders "Never".
            when(sessionRepository.lastSignInByAccount()).thenReturn(Map.of());

            mvc.perform(get("/api/v1/admin/accounts").with(superAdmin()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].lastSignInAt").doesNotExist());
        }
    }

    @Nested
    @DisplayName("POST /api/v1/admin/accounts")
    class CreateAccount {

        @Test
        @DisplayName("creates the account and answers 201 with its location")
        void creates() throws Exception {
            when(accountService.create(eq("new.person@example.com"), eq(AppRole.ADMIN), any()))
                    .thenReturn(account("new.person@example.com", AppRole.ADMIN));

            mvc.perform(post("/api/v1/admin/accounts")
                            .with(superAdmin())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"email":"new.person@example.com","role":"admin"}"""))
                    .andExpect(status().isCreated())
                    .andExpect(header().string("Location", "/api/v1/admin/accounts/" + ACCOUNT_ID))
                    .andExpect(jsonPath("$.email").value("new.person@example.com"))
                    .andExpect(jsonPath("$.role").value("admin"));
        }

        @Test
        @DisplayName("refuses an unknown role without creating anything")
        void rejectsUnknownRole() throws Exception {
            mvc.perform(post("/api/v1/admin/accounts")
                            .with(superAdmin())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"email":"new.person@example.com","role":"owner"}"""))
                    .andExpect(status().isBadRequest());

            // The role is parsed before anything is written, so a typo cannot
            // leave an account behind at some other role.
            verify(accountService, never()).create(any(), any(), any());
        }

        @Test
        @DisplayName("refuses an address that is not an address")
        void rejectsMalformedEmail() throws Exception {
            mvc.perform(post("/api/v1/admin/accounts")
                            .with(superAdmin())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"email":"not-an-address","role":"user"}"""))
                    .andExpect(status().isBadRequest());

            verify(accountService, never()).create(any(), any(), any());
        }

        @Test
        @DisplayName("answers 409 when the address already has an account")
        void rejectsDuplicate() throws Exception {
            when(accountService.create(any(), any(), any()))
                    .thenThrow(ApiException.conflict("that email address already has an account"));

            mvc.perform(post("/api/v1/admin/accounts")
                            .with(superAdmin())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"email":"taken@example.com","role":"user"}"""))
                    .andExpect(status().isConflict())
                    // The screen shows this wording directly, so it has to say
                    // what to do differently rather than just "failed".
                    .andExpect(jsonPath("$.detail").value("that email address already has an account"));
        }
    }

    @Nested
    @DisplayName("PATCH /api/v1/admin/accounts/{accountId}")
    class UpdateAccount {

        @Test
        @DisplayName("assigns a new role and returns the updated account")
        void assignsRole() throws Exception {
            when(accountService.update(eq(ACCOUNT_ID), eq(EnumSet.of(AppRole.ADMIN)), isNull(), isNull(), isNull(), any()))
                    .thenReturn(account("person@example.com", AppRole.ADMIN));

            mvc.perform(patch("/api/v1/admin/accounts/" + ACCOUNT_ID)
                            .with(superAdmin())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"role":"admin"}"""))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(ACCOUNT_ID.toString()))
                    .andExpect(jsonPath("$.role").value("admin"));
        }

        @Test
        @DisplayName("assigns a whole set of roles via `roles`, not just the single `role` field")
        void assignsMultipleRoles() throws Exception {
            when(accountService.update(
                            eq(ACCOUNT_ID),
                            eq(new java.util.LinkedHashSet<>(List.of(AppRole.USER, AppRole.ADMIN))),
                            isNull(),
                            isNull(),
                            isNull(),
                            any()))
                    .thenReturn(account("person@example.com", AppRole.ADMIN));

            mvc.perform(patch("/api/v1/admin/accounts/" + ACCOUNT_ID)
                            .with(superAdmin())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"roles":["user","admin"]}"""))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.role").value("admin"));
        }

        @Test
        @DisplayName("answers 400 when roles is an empty list")
        void rejectsEmptyRolesList() throws Exception {
            mvc.perform(patch("/api/v1/admin/accounts/" + ACCOUNT_ID)
                            .with(superAdmin())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"roles":[]}"""))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("deactivates an account and returns it with active:false")
        void deactivatesAccount() throws Exception {
            Account deactivated = new Account(
                    ACCOUNT_ID, "person@example.com", "Person",
                    EnumSet.of(AppRole.USER), null, null, AccountStatus.ACTIVE,
                    Instant.parse("2026-09-14T10:00:00Z"),
                    null,
                    Instant.parse("2026-09-01T09:00:00Z"),
                    Instant.parse("2026-09-14T10:00:00Z"),
                    false,
                    true);

            when(accountService.update(eq(ACCOUNT_ID), isNull(), eq(false), isNull(), isNull(), any()))
                    .thenReturn(deactivated);

            mvc.perform(patch("/api/v1/admin/accounts/" + ACCOUNT_ID)
                            .with(superAdmin())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"active":false}"""))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.active").value(false));
        }

        @Test
        @DisplayName("answers 400 when the request carries no fields to change")
        void rejectsEmptyPatch() throws Exception {
            mvc.perform(patch("/api/v1/admin/accounts/" + ACCOUNT_ID)
                            .with(superAdmin())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("answers 400 when the role name is unrecognised")
        void rejectsUnknownRole() throws Exception {
            mvc.perform(patch("/api/v1/admin/accounts/" + ACCOUNT_ID)
                            .with(superAdmin())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"role":"owner"}"""))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("answers 409 when demoting or deactivating the last active super admin")
        // req: UC-004 E-4, FR-AUTH-008
        void refusesLastSuperAdmin() throws Exception {
            when(accountService.update(eq(ACCOUNT_ID), any(), any(), any(), any(), any()))
                    .thenThrow(ApiException.conflict(
                            "this is the only active super admin; promote someone else first"));

            mvc.perform(patch("/api/v1/admin/accounts/" + ACCOUNT_ID)
                            .with(superAdmin())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"role":"admin"}"""))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.detail").value(
                            org.hamcrest.Matchers.containsString("only active super admin")));
        }
    }

    // -----------------------------------------------------------------------
    // FR-AUTH-008 boundary — the test that proves it
    // -----------------------------------------------------------------------
    // An admin must NOT reach account management. Only super_admin. Hiding the
    // menu in the UI is not the control; this is.
    //
    // req: FR-AUTH-008

    @Nested
    @DisplayName("FR-AUTH-008: admin is blocked from every account management endpoint")
    class AdminIsBlocked {

        private static RequestPostProcessor admin() {
            return SecurityMockMvcRequestPostProcessors.authentication(
                    com.coc.docs.auth.TestCallers.authentication(AppRole.ADMIN));
        }

        @Test
        @DisplayName("GET /api/v1/admin/accounts is denied to admin")
        void list_denied_to_admin() throws Exception {
            mvc.perform(get("/api/v1/admin/accounts").with(admin()))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("POST /api/v1/admin/accounts is denied to admin")
        void create_denied_to_admin() throws Exception {
            mvc.perform(post("/api/v1/admin/accounts")
                            .with(admin())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"email":"x@example.com","role":"user"}"""))
                    .andExpect(status().isForbidden());

            verify(accountService, never()).create(any(), any(), any());
        }

        @Test
        @DisplayName("PATCH /api/v1/admin/accounts/{id} is denied to admin")
        void update_denied_to_admin() throws Exception {
            mvc.perform(patch("/api/v1/admin/accounts/" + ACCOUNT_ID)
                            .with(admin())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"role":"super_admin"}"""))
                    .andExpect(status().isForbidden());

            verify(accountService, never()).update(any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("GET /api/v1/admin/accounts/pending is denied to admin")
        void pending_queue_denied_to_admin() throws Exception {
            mvc.perform(get("/api/v1/admin/accounts/pending").with(admin()))
                    .andExpect(status().isForbidden());
        }
    }
}
