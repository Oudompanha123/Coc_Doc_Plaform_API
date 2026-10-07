package com.coc.docs.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.coc.docs.account.AppRole;
import com.coc.docs.announcement.Announcement;
import com.coc.docs.announcement.AnnouncementService;
import com.coc.docs.auth.SessionAuthenticationFilter;
import com.coc.docs.auth.SessionService;
import com.coc.docs.auth.TestCallers;
import com.coc.docs.config.AppProperties;
import com.coc.docs.config.ProblemResponses;
import com.coc.docs.config.SecurityConfig;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
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
 * Who may reach the announcement routes (ADR-098): every signed-in role reads,
 * only an admin writes. The floors are {@code SecurityConfig}'s, so that is
 * what this imports and exercises; the service is mocked.
 *
 * <p>// req: ADR-098
 */
@WebMvcTest({AnnouncementController.class, AdminAnnouncementController.class})
@Import({SecurityConfig.class, ProblemResponses.class, AnnouncementControllerTest.Beans.class})
class AnnouncementControllerTest {

    @Autowired MockMvc mvc;

    @MockitoBean AnnouncementService announcements;
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

    private static RequestPostProcessor as(AppRole role) {
        return SecurityMockMvcRequestPostProcessors.authentication(TestCallers.authentication(role));
    }

    private static Announcement live(UUID id) {
        Instant now = Instant.now();
        return new Announcement(
                id, "New feature", "Dark mode is here", "update", Announcement.STATUS_PUBLISHED, null, 2,
                now, "Admin", now, now, "Admin");
    }

    @Test
    @DisplayName("a plain user reads live announcements, without editor names or status")
    void userReadsLive() throws Exception {
        UUID id = UUID.randomUUID();
        when(announcements.liveForReaders()).thenReturn(List.of(live(id)));

        mvc.perform(get("/api/v1/announcements").with(as(AppRole.USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(id.toString()))
                .andExpect(jsonPath("$[0].title").value("New feature"))
                .andExpect(jsonPath("$[0].status").doesNotExist())
                .andExpect(jsonPath("$[0].publishedByName").doesNotExist());
    }

    @Test
    @DisplayName("a plain user reads one live announcement by id")
    void userReadsOne() throws Exception {
        UUID id = UUID.randomUUID();
        when(announcements.liveForReader(id)).thenReturn(live(id));

        mvc.perform(get("/api/v1/announcements/" + id).with(as(AppRole.USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body").value("Dark mode is here"));
    }

    @Test
    @DisplayName("a plain user cannot reach the admin list, nor publish")
    void userCannotManage() throws Exception {
        mvc.perform(get("/api/v1/admin/announcements").with(as(AppRole.USER)))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/admin/announcements/" + UUID.randomUUID() + "/publish").with(as(AppRole.USER)))
                .andExpect(status().isForbidden());
        verifyNoInteractions(announcements);
    }

    @Test
    @DisplayName("an admin creates a draft")
    void adminCreates() throws Exception {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        when(announcements.create(any(), any()))
                .thenReturn(new Announcement(id, "Hello", "World", "info", Announcement.STATUS_DRAFT, null, 1,
                        null, null, now, now, "Admin"));

        mvc.perform(post("/api/v1/admin/announcements")
                        .with(as(AppRole.ADMIN))
                        .contentType("application/json")
                        .content("{\"title\":\"Hello\",\"body\":\"World\",\"category\":\"info\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("draft"))
                .andExpect(jsonPath("$.versionNo").value(1));
    }

    @Test
    @DisplayName("an empty title never reaches the service")
    void blankTitleRefused() throws Exception {
        mvc.perform(post("/api/v1/admin/announcements")
                        .with(as(AppRole.ADMIN))
                        .contentType("application/json")
                        .content("{\"title\":\"\",\"body\":\"World\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(announcements);
    }

    @Test
    @DisplayName("an admin deletes")
    void adminDeletes() throws Exception {
        UUID id = UUID.randomUUID();
        mvc.perform(delete("/api/v1/admin/announcements/" + id).with(as(AppRole.ADMIN)))
                .andExpect(status().isNoContent());
        verify(announcements).delete(eq(id), any());
    }
}
