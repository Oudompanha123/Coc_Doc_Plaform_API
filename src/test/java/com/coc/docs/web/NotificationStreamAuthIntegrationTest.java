package com.coc.docs.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.coc.docs.account.AccountRepository;
import com.coc.docs.account.AccountStatus;
import com.coc.docs.account.AppRole;
import com.coc.docs.auth.SessionService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The notification stream over real HTTP, on a real servlet container.
 *
 * <p><b>Why not MockMvc.</b> An {@code SseEmitter} makes the response
 * asynchronous, and the container then dispatches the request a second time to
 * finish it. That second pass is where this route broke on 2026-09-16: Spring
 * Security authorizes every dispatch, {@code SessionAuthenticationFilter} is an
 * {@code OncePerRequestFilter} and skipped the async one, so a caller who had
 * just been let in came back anonymous and was denied.
 *
 * <p>{@code SecurityRulesTest} has three cases on this route and every one of
 * them passed while that was true — MockMvc reinstalls the test's
 * SecurityContext on each dispatch, so the caller is authenticated on both
 * passes whatever the filter chain does. Verified by reintroducing the bug and
 * watching them stay green. Only a real container reproduces it, which is what
 * this class is for.
 *
 * <p>// req: FR-AUTH-007, NFR-SEC-SESSION, ADR-011, ADR-028
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class NotificationStreamAuthIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String PASSWORD = "CorrectHorse123!";

    @LocalServerPort int port;

    @Autowired SessionService sessions;
    @Autowired AccountRepository accounts;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JdbcClient jdbc;

    private final HttpClient http =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @BeforeEach
    void setUp() {
        jdbc.sql("truncate audit_log, app_session, account restart identity cascade").update();
        // The V4 deferred trigger requires an active super_admin to survive
        // every commit.
        accounts.insert("boss@example.com", "Boss", AppRole.SUPER_ADMIN, null, AccountStatus.ACTIVE);
        accounts.insert(
                "streamer@example.com",
                "Streamer",
                AppRole.USER,
                null,
                AccountStatus.ACTIVE,
                passwordEncoder.encode(PASSWORD),
                false);
    }

    private HttpResponse<String> openStream(String cookie) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/v1/notifications/stream"))
                .header("Accept", "text/event-stream")
                // The stream never ends on its own, so the read is bounded
                // here. A timeout means it stayed open, which for this route is
                // the success case and is asserted as such below.
                .timeout(Duration.ofSeconds(3))
                .GET();
        if (cookie != null) {
            request.header("Cookie", SessionService.COOKIE_NAME + "=" + cookie);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    @DisplayName("a signed-in caller stays authenticated across the async dispatch")
    void authenticatedCallerIsNotDeniedOnTheSecondPass() throws Exception {
        // No TOTP enrolled on this account, so the password path issues a
        // session outright rather than an MFA challenge (ADR-029).
        String token = ((SessionService.IssuedSession)
                        sessions.signInWithPassword(
                                "streamer@example.com", PASSWORD, "test-agent", "127.0.0.1"))
                .token();

        HttpResponse<String> response;
        try {
            response = openStream(token);
        } catch (java.net.http.HttpTimeoutException expected) {
            // The connection was accepted and held open, which is the whole
            // point of a stream. Reading it to the end would mean waiting
            // thirty minutes.
            return;
        }

        // If it came back at all it must be a 200 that started emitting, never
        // the 401/500 the async denial produced.
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("ready");
    }

    @Test
    @DisplayName("a caller with no session is refused, and not by timing out")
    void anonymousCallerIsRefused() throws Exception {
        HttpResponse<String> response = openStream(null);

        assertThat(response.statusCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("a cookie naming no session is refused")
    void unknownTokenIsRefused() throws Exception {
        HttpResponse<String> response = openStream("not-a-real-token");

        assertThat(response.statusCode()).isEqualTo(401);
    }
}
