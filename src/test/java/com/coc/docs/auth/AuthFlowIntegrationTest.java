package com.coc.docs.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;

import com.coc.docs.account.AccountPrincipal;
import com.coc.docs.account.Role;
import com.coc.docs.auth.dto.AuthResponse;
import com.coc.docs.auth.dto.LoginRequest;
import com.coc.docs.auth.dto.NonceResponse;
import com.coc.docs.auth.dto.PasswordLoginRequest;
import com.coc.docs.auth.dto.RegisterRequest;
import com.coc.docs.auth.dto.RegisterResponse;
import com.coc.docs.testsupport.KeycloakTestTokens;
import com.coc.docs.web.ApiError;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * End-to-end against a real, Flyway-migrated Postgres via Testcontainers —
 * required by ADR-003 (jsonb/partial-index/deferred-constraint schema that H2
 * doesn't faithfully reproduce; this project carries no H2 dependency at all).
 *
 * <p>Network-free: the {@link JwtDecoder} bean is replaced with one trusting
 * only a locally generated key ({@link KeycloakTestTokens}), so nothing here
 * depends on reaching a real Keycloak realm's JWKS endpoint — that
 * autoconfigured wiring is Spring's own well-tested code; this class verifies
 * the login API's own logic on top of it.
 *
 * <p>{@code disabledWithoutDocker = true}: skips cleanly on a machine with no
 * Docker (this sandbox, notably) instead of failing.
 *
 * // req: FR-AUTH-001, FR-AUTH-002, FR-AUTH-007, NFR-SEC-SESSION; UC-004 TC-004-01/02/04/08
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@Import(AuthFlowIntegrationTest.TestJwtConfig.class)
class AuthFlowIntegrationTest {

    private static final KeycloakTestTokens TOKENS = new KeycloakTestTokens();
    private static final String ACTIVE_EMAIL = "admin@example.com";
    private static final String DEACTIVATED_EMAIL = "gone@example.com";
    private static final String SUPER_ADMIN_EMAIL = "root@example.com";

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:15-alpine"));

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        // Non-blank on purpose: exercises the real audience-mismatch path
        // rather than the (separately, unit-tested) fail-fast startup guard.
        registry.add("spring.security.oauth2.resourceserver.jwt.audiences", () -> KeycloakTestTokens.AUDIENCE);
    }

    /** Swaps out the real, network-backed autoconfigured JwtDecoder for one trusting only our test key. */
    @TestConfiguration
    static class TestJwtConfig {
        @Bean
        JwtDecoder jwtDecoder() {
            return TOKENS.decoder();
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    private UUID superAdminId;

    @BeforeEach
    void seedAccounts() {
        jdbcTemplate.update("delete from app_session");
        jdbcTemplate.update("delete from audit_log");
        jdbcTemplate.update("delete from account");
        jdbcTemplate.update(
                "insert into account (email, display_name, role) values (?, ?, 'admin')",
                ACTIVE_EMAIL,
                "Active Admin");
        // TC-004-04/E-3: a deactivated account must fail sign-in the same way as no account at all.
        jdbcTemplate.update(
                "insert into account (email, display_name, role, deactivated_at) values (?, ?, 'user', now())",
                DEACTIVATED_EMAIL,
                "Gone User");
        // ADR-014: approving/rejecting a registration is a super_admin-only action.
        superAdminId = jdbcTemplate.queryForObject(
                "insert into account (email, display_name, role) values (?, ?, 'super_admin') returning id",
                UUID.class,
                SUPER_ADMIN_EMAIL,
                "Root Admin");
    }

    @Test
    void signInIssuesASessionCookieAndReturnsTheAccount() throws Exception {
        NonceExchange nonce = requestNonce();

        MockHttpServletResponse response = login(idTokenFor(ACTIVE_EMAIL, nonce.value()), nonce.cookie());

        assertThat(response.getStatus()).isEqualTo(200);
        Cookie sessionCookie = response.getCookie("coc_session");
        assertThat(sessionCookie).isNotNull();
        assertThat(sessionCookie.isHttpOnly()).isTrue();

        AuthResponse body = objectMapper.readValue(response.getContentAsString(), AuthResponse.class);
        assertThat(body.email()).isEqualTo(ACTIVE_EMAIL);
        assertThat(body.role()).isEqualTo(Role.ADMIN);

        mockMvc.perform(get("/api/auth/me").cookie(sessionCookie))
                .andExpect(status().isOk());
    }

    @Test
    void signInForAnUnprovisionedEmailIsRefusedAndLogged() throws Exception {
        // UC-004 E-1 / TC-004-02: a valid identity with no matching account grants nothing.
        NonceExchange nonce = requestNonce();

        MockHttpServletResponse response = login(idTokenFor("nobody@example.com", nonce.value()), nonce.cookie());

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getCookie("coc_session")).isNull();
        assertThat(response.getContentAsString())
                .isEqualTo(objectMapper.writeValueAsString(new ApiError("Sign-in failed")));
        assertAuditLogged("auth.login.failure", "no_account");
    }

    @Test
    void signInForADeactivatedAccountGetsTheSameRefusalAsForNoAccount() throws Exception {
        // UC-004 E-3: refused with a neutral message; no information about account state leaks.
        NonceExchange nonce = requestNonce();

        MockHttpServletResponse response = login(idTokenFor(DEACTIVATED_EMAIL, nonce.value()), nonce.cookie());

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString())
                .isEqualTo(objectMapper.writeValueAsString(new ApiError("Sign-in failed")));
        assertAuditLogged("auth.login.failure", "deactivated");
    }

    @Test
    void aRequestWithNoSessionCookieIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void signOutInvalidatesTheSessionImmediately() throws Exception {
        // TC-004-08.
        NonceExchange nonce = requestNonce();
        MockHttpServletResponse loginResponse = login(idTokenFor(ACTIVE_EMAIL, nonce.value()), nonce.cookie());
        Cookie sessionCookie = loginResponse.getCookie("coc_session");

        mockMvc.perform(post("/api/auth/logout").cookie(sessionCookie)).andExpect(status().isNoContent());

        mockMvc.perform(get("/api/auth/me").cookie(sessionCookie)).andExpect(status().isUnauthorized());
    }

    // -------------------------------------------------------------------
    // ADR-014: password self-registration + super_admin approval
    // -------------------------------------------------------------------

    @Test
    void registrationCreatesAPendingAccountThatCannotSignInYet() throws Exception {
        MockHttpServletResponse response = register("new@example.com", "New Person", "correct horse battery");

        assertThat(response.getStatus()).isEqualTo(201);
        RegisterResponse body = objectMapper.readValue(response.getContentAsString(), RegisterResponse.class);
        assertThat(body.status()).isEqualTo("PENDING");

        MockHttpServletResponse loginResponse = loginWithPassword("new@example.com", "correct horse battery");
        assertThat(loginResponse.getStatus()).isEqualTo(401);
        assertAuditLogged("auth.login.failure", "pending_approval");
    }

    @Test
    void registeringTheSameEmailTwiceIsRefused() throws Exception {
        register("dup@example.com", "First", "correct horse battery");

        MockHttpServletResponse second = register("dup@example.com", "Second", "another password");

        assertThat(second.getStatus()).isEqualTo(409);
    }

    @Test
    void approvingARegistrationLetsItSignInWithPassword() throws Exception {
        register("approved@example.com", "Approved Person", "correct horse battery");
        UUID accountId = jdbcTemplate.queryForObject(
                "select id from account where email = ?", UUID.class, "approved@example.com");

        mockMvc.perform(post("/api/admin/accounts/{id}/approve", accountId).with(asSuperAdmin()))
                .andExpect(status().isOk());

        MockHttpServletResponse loginResponse = loginWithPassword("approved@example.com", "correct horse battery");
        assertThat(loginResponse.getStatus()).isEqualTo(200);
        assertThat(loginResponse.getCookie("coc_session")).isNotNull();
    }

    @Test
    void aWrongPasswordAndAPendingAccountGetTheSameGenericRefusal() throws Exception {
        register("wrongpw@example.com", "Someone", "correct horse battery");
        UUID accountId = jdbcTemplate.queryForObject(
                "select id from account where email = ?", UUID.class, "wrongpw@example.com");
        mockMvc.perform(post("/api/admin/accounts/{id}/approve", accountId).with(asSuperAdmin()))
                .andExpect(status().isOk());

        MockHttpServletResponse response = loginWithPassword("wrongpw@example.com", "not the password");

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString())
                .isEqualTo(objectMapper.writeValueAsString(new ApiError("Sign-in failed")));
    }

    @Test
    void rejectingARegistrationPermanentlyBlocksPasswordSignIn() throws Exception {
        register("rejected@example.com", "Rejected Person", "correct horse battery");
        UUID accountId = jdbcTemplate.queryForObject(
                "select id from account where email = ?", UUID.class, "rejected@example.com");

        mockMvc.perform(post("/api/admin/accounts/{id}/reject", accountId).with(asSuperAdmin()))
                .andExpect(status().isOk());

        MockHttpServletResponse loginResponse = loginWithPassword("rejected@example.com", "correct horse battery");
        assertThat(loginResponse.getStatus()).isEqualTo(401);
        assertAuditLogged("auth.login.failure", "rejected");
    }

    @Test
    void onlyASuperAdminCanApproveARegistration() throws Exception {
        register("needsreview@example.com", "Needs Review", "correct horse battery");
        UUID accountId = jdbcTemplate.queryForObject(
                "select id from account where email = ?", UUID.class, "needsreview@example.com");
        AccountPrincipal regularAdmin = new AccountPrincipal(UUID.randomUUID(), ACTIVE_EMAIL, "Active Admin", Role.ADMIN);
        var adminAuth = new UsernamePasswordAuthenticationToken(
                regularAdmin, null, List.of(new SimpleGrantedAuthority(Role.ADMIN.authority())));

        mockMvc.perform(post("/api/admin/accounts/{id}/approve", accountId).with(authentication(adminAuth)))
                .andExpect(status().isForbidden());
    }

    private RequestPostProcessor asSuperAdmin() {
        AccountPrincipal principal = new AccountPrincipal(superAdminId, SUPER_ADMIN_EMAIL, "Root Admin", Role.SUPER_ADMIN);
        var authorities = List.of(new SimpleGrantedAuthority(Role.SUPER_ADMIN.authority()));
        return authentication(new UsernamePasswordAuthenticationToken(principal, null, authorities));
    }

    private MockHttpServletResponse register(String email, String displayName, String password) throws Exception {
        return mockMvc
                .perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RegisterRequest(email, displayName, password))))
                .andReturn()
                .getResponse();
    }

    private MockHttpServletResponse loginWithPassword(String email, String password) throws Exception {
        return mockMvc
                .perform(post("/api/auth/login-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new PasswordLoginRequest(email, password))))
                .andReturn()
                .getResponse();
    }

    private record NonceExchange(String value, Cookie cookie) {}

    private NonceExchange requestNonce() throws Exception {
        MockHttpServletResponse response = mockMvc.perform(post("/api/auth/nonce")).andReturn().getResponse();
        NonceResponse body = objectMapper.readValue(response.getContentAsString(), NonceResponse.class);
        return new NonceExchange(body.nonce(), response.getCookie("coc_oidc_nonce"));
    }

    private String idTokenFor(String email, String nonce) {
        Map<String, Object> claims = TOKENS.defaultClaims();
        claims.put("email", email);
        claims.put("nonce", nonce);
        return TOKENS.sign(claims);
    }

    private MockHttpServletResponse login(String idToken, Cookie nonceCookie) throws Exception {
        return mockMvc
                .perform(post("/api/auth/login")
                        .cookie(nonceCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(idToken))))
                .andReturn()
                .getResponse();
    }

    private void assertAuditLogged(String action, String reasonContains) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "select detail from audit_log where action = ? order by occurred_at desc limit 1", action);
        assertThat(rows).isNotEmpty();
        assertThat(String.valueOf(rows.get(0).get("detail"))).contains(reasonContains);
    }
}
