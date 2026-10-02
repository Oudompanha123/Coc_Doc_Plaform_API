package com.coc.docs.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.coc.docs.account.AppRole;
import com.coc.docs.auth.SessionAuthenticationFilter;
import com.coc.docs.auth.SessionService;
import com.coc.docs.auth.TestCallers;
import com.coc.docs.config.AppProperties;
import com.coc.docs.config.ProblemResponses;
import com.coc.docs.config.SecurityConfig;
import com.coc.docs.notify.ActivityDigestService;
import java.time.Duration;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * The on-demand digest route, with no database and no mail server.
 *
 * <p>What is under test here is the route's own decisions — which day it picks
 * when asked for none, what it does with a day it cannot use — not what the
 * digest does, which is {@code ActivityDigestServiceTest}'s subject, nor who
 * may call it, which is {@link SecurityRulesTest}'s.
 *
 * <p>// req: FR-OPS-001
 */
@WebMvcTest(AdminNotificationController.class)
@Import({SecurityConfig.class, ProblemResponses.class, AdminNotificationControllerTest.Beans.class})
class AdminNotificationControllerTest {

    private static final LocalDate YESTERDAY = LocalDate.of(2026, 9, 14);

    @Autowired MockMvc mvc;

    @MockitoBean ActivityDigestService digest;
    @MockitoBean SessionService sessionService;

    @TestConfiguration
    static class Beans {

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

        @Bean
        SessionAuthenticationFilter sessionAuthenticationFilter(SessionService sessions) {
            return new SessionAuthenticationFilter(sessions);
        }
    }

    private static RequestPostProcessor superAdmin() {
        return SecurityMockMvcRequestPostProcessors.authentication(
                TestCallers.authentication(AppRole.SUPER_ADMIN));
    }

    private static ActivityDigestService.DigestResult result(
            LocalDate day, ActivityDigestService.Outcome outcome) {
        return new ActivityDigestService.DigestResult(day, outcome, 3, 2, 4);
    }

    @BeforeEach
    void setUp() {
        when(digest.yesterday()).thenReturn(YESTERDAY);
        when(digest.sendDigestFor(any(LocalDate.class), any()))
                .thenAnswer(i -> result(i.getArgument(0), ActivityDigestService.Outcome.SENT));
    }

    @Test
    @DisplayName("with no day, reports on yesterday — the day the schedule would have covered")
    void defaultsToYesterday() throws Exception {
        mvc.perform(post("/api/v1/admin/notifications/digest").with(superAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.day").value("2026-09-14"))
                .andExpect(jsonPath("$.sent").value(true))
                .andExpect(jsonPath("$.pagesChanged").value(3))
                .andExpect(jsonPath("$.sections").value(2))
                .andExpect(jsonPath("$.recipients").value(4))
                .andExpect(jsonPath("$.reason").value("sent"));

        verify(digest).sendDigestFor(eq(YESTERDAY), any());
    }

    @Test
    @DisplayName("an explicit past day is used as given")
    void honoursAnExplicitDay() throws Exception {
        mvc.perform(post("/api/v1/admin/notifications/digest?day=2026-09-01").with(superAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.day").value("2026-09-01"));

        verify(digest).sendDigestFor(eq(LocalDate.of(2026, 9, 1)), any());
    }

    @Test
    @DisplayName("today is allowed — it simply covers the hours so far")
    void allowsToday() throws Exception {
        mvc.perform(post("/api/v1/admin/notifications/digest?day=2026-09-15").with(superAdmin()))
                .andExpect(status().isOk());

        verify(digest).sendDigestFor(eq(LocalDate.of(2026, 9, 15)), any());
    }

    @Test
    @DisplayName("a future day is refused with 400, not answered with an empty report")
    void refusesAFutureDay() throws Exception {
        mvc.perform(post("/api/v1/admin/notifications/digest?day=2026-09-16").with(superAdmin()))
                .andExpect(status().isBadRequest());

        // Nothing was sent to anyone on the way to the refusal.
        verify(digest, never()).sendDigestFor(any(LocalDate.class), any());
    }

    @Test
    @DisplayName("a malformed day is 400, not the catch-all's 500")
    void refusesAMalformedDay() throws Exception {
        // Bound as a String and parsed in the controller precisely for this:
        // letting Spring bind a LocalDate directly makes the failure a
        // MethodArgumentTypeMismatchException, which ApiExceptionHandler's
        // catch-all reports as "an unexpected error occurred" with a 500.
        mvc.perform(post("/api/v1/admin/notifications/digest?day=14-09-2026").with(superAdmin()))
                .andExpect(status().isBadRequest());

        verify(digest, never()).sendDigestFor(any(LocalDate.class), any());
    }

    @Test
    @DisplayName("an empty day parameter falls back to yesterday rather than failing")
    void treatsBlankDayAsAbsent() throws Exception {
        mvc.perform(post("/api/v1/admin/notifications/digest?day=").with(superAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.day").value("2026-09-14"));
    }

    @Test
    @DisplayName("'not sent' comes back as 200 with the reason, not as an error")
    void reportsWhyNothingWasSent() throws Exception {
        when(digest.sendDigestFor(any(LocalDate.class), any()))
                .thenReturn(new ActivityDigestService.DigestResult(
                        YESTERDAY, ActivityDigestService.Outcome.NO_ACTIVITY, 0, 0, 0));

        // The request succeeded; there was simply nothing to report. A 4xx here
        // would tell the caller they did something wrong, and they did not.
        mvc.perform(post("/api/v1/admin/notifications/digest").with(superAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sent").value(false))
                .andExpect(jsonPath("$.reason").value("no page activity on this day"));
    }

    @Test
    @DisplayName("a missing recipient list is distinguished from a refused relay")
    void distinguishesNotSentReasons() throws Exception {
        when(digest.sendDigestFor(any(LocalDate.class), any()))
                .thenReturn(new ActivityDigestService.DigestResult(
                        YESTERDAY, ActivityDigestService.Outcome.NOT_DELIVERED, 3, 2, 4));

        mvc.perform(post("/api/v1/admin/notifications/digest").with(superAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sent").value(false))
                .andExpect(jsonPath("$.reason").value(org.hamcrest.Matchers.containsString("SMTP")));
    }
}
