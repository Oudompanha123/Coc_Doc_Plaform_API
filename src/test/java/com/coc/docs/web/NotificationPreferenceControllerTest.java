package com.coc.docs.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.coc.docs.account.AppRole;
import com.coc.docs.auth.SessionAuthenticationFilter;
import com.coc.docs.auth.SessionService;
import com.coc.docs.auth.TestCallers;
import com.coc.docs.config.AppProperties;
import com.coc.docs.config.ProblemResponses;
import com.coc.docs.config.SecurityConfig;
import com.coc.docs.notify.NotificationPreferenceService;
import com.coc.docs.notify.NotificationType;
import java.time.Duration;
import java.util.List;
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
 * The caller's own notification-preference route (ADR-041).
 *
 * <p>What is under test is this route's own decisions — the response shape,
 * and a 404 rather than the catch-all's 500 for an unrecognised type — not
 * whether a preference is actually persisted or gates delivery, which is
 * {@code NotificationPreferenceRepositoryIntegrationTest}'s and {@code
 * NotificationServiceTest}'s subject respectively.
 *
 * <p>// req: ADR-041
 */
@WebMvcTest(NotificationPreferenceController.class)
@Import({SecurityConfig.class, ProblemResponses.class, NotificationPreferenceControllerTest.Beans.class})
class NotificationPreferenceControllerTest {

    @Autowired MockMvc mvc;

    @MockitoBean NotificationPreferenceService preferences;
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

    private static RequestPostProcessor asUser() {
        return SecurityMockMvcRequestPostProcessors.authentication(TestCallers.authentication(AppRole.USER));
    }

    @Test
    @DisplayName("lists every real notification type, defaulted to enabled")
    void listsEveryType() throws Exception {
        when(preferences.listFor(any()))
                .thenReturn(List.of(
                        new NotificationPreferenceService.Preference(NotificationType.NEW_REGISTRATION, true),
                        new NotificationPreferenceService.Preference(NotificationType.NEW_DEVICE_SIGN_IN, false)));

        mvc.perform(get("/api/v1/notifications/preferences").with(asUser()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].type").value("registration.pending"))
                .andExpect(jsonPath("$.items[0].enabled").value(true))
                .andExpect(jsonPath("$.items[1].type").value("auth.new-device"))
                .andExpect(jsonPath("$.items[1].enabled").value(false));
    }

    @Test
    @DisplayName("turns a known type off for the caller")
    void turnsATypeOff() throws Exception {
        mvc.perform(put("/api/v1/notifications/preferences/registration.pending")
                        .with(asUser())
                        .contentType("application/json")
                        .content("{\"enabled\":false}"))
                .andExpect(status().isNoContent());

        verify(preferences).setEnabled(any(), eq(NotificationType.NEW_REGISTRATION), eq(false));
    }

    @Test
    @DisplayName("turns a known type back on for the caller")
    void turnsATypeOn() throws Exception {
        mvc.perform(put("/api/v1/notifications/preferences/auth.new-device")
                        .with(asUser())
                        .contentType("application/json")
                        .content("{\"enabled\":true}"))
                .andExpect(status().isNoContent());

        verify(preferences).setEnabled(any(), eq(NotificationType.NEW_DEVICE_SIGN_IN), eq(true));
    }

    @Test
    @DisplayName("an unrecognised type is a 404, not the catch-all's 500")
    void refusesAnUnknownType() throws Exception {
        mvc.perform(put("/api/v1/notifications/preferences/not-a-real-type")
                        .with(asUser())
                        .contentType("application/json")
                        .content("{\"enabled\":false}"))
                .andExpect(status().isNotFound());
    }
}
