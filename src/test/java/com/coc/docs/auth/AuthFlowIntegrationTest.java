package com.coc.docs.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.coc.docs.account.Role;
import com.coc.docs.auth.dto.AuthResponse;
import com.coc.docs.auth.dto.LoginRequest;
import com.coc.docs.auth.dto.NonceResponse;
import com.coc.docs.testsupport.GoogleTestTokens;
import com.coc.docs.web.ApiError;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.Map;
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
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
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
 * only a locally generated key ({@link GoogleTestTokens}), so nothing here
 * depends on reaching the real {@code accounts.google.com} JWKS endpoint —
 * that autoconfigured wiring is Spring's own well-tested code; this class
 * verifies the login API's own logic on top of it.
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

    private static final GoogleTestTokens TOKENS = new GoogleTestTokens();
    private static final String ACTIVE_EMAIL = "admin@example.com";
    private static final String DEACTIVATED_EMAIL = "gone@example.com";

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
        registry.add("spring.security.oauth2.resourceserver.jwt.audiences", () -> GoogleTestTokens.AUDIENCE);
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
        // UC-004 E-1 / TC-004-02: a valid Google identity with no matching account grants nothing.
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
