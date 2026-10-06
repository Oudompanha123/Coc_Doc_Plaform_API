package com.coc.docs.web;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.coc.docs.auth.SessionAuthenticationFilter;
import com.coc.docs.auth.SessionService;
import com.coc.docs.config.AppProperties;
import com.coc.docs.config.ProblemResponses;
import com.coc.docs.config.SecurityConfig;
import com.coc.docs.docscontent.DocsContentService;
import java.time.Duration;
import java.util.List;
import java.util.Set;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The bulk existence-check route ({@code GET /api/v1/docs-content/{productKey}?slugs=...}),
 * added so {@code ProductContentTable} can render every row's "Has
 * content"/"Not written" status in one round trip instead of one 404-prone
 * {@code GET .../{slug}} per row.
 *
 * <p>This route is {@code permitAll} (see {@link DocsContentController}'s own
 * javadoc and the matching rule in {@link SecurityConfig}), so no
 * authenticated principal is attached to any request here — that absence is
 * itself part of what is under test, the same way {@link
 * AdminNotificationControllerTest} exercises an authenticated one.
 *
 * <p>What is under test is this route's own decisions — how it parses {@code
 * slugs} and that it always answers {@code 200} — not whether a given slug
 * actually has a saved row, which is {@code DocsContentRepositoryIntegrationTest}'s
 * subject against a real database.
 *
 * <p>// req: ADR-012 investigation, 2026-09-15
 */
@WebMvcTest(DocsContentController.class)
@Import({SecurityConfig.class, ProblemResponses.class, DocsContentControllerTest.Beans.class})
class DocsContentControllerTest {

    @Autowired MockMvc mvc;

    @MockitoBean DocsContentService content;
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

    @BeforeEach
    void setUp() {
        // The controller always calls through, however it parsed `slugs` — a
        // lenient default so a test that only cares about the HTTP shape
        // (status, JSON key present) does not also have to stub the call.
        when(content.findSavedSlugs(any(), any())).thenReturn(Set.of());
    }

    @Test
    @DisplayName("every requested slug has a saved row")
    void allSlugsExist() throws Exception {
        when(content.findSavedSlugs("jex3-0", List.of("get-started", "flow")))
                .thenReturn(Set.of("get-started", "flow"));

        mvc.perform(get("/api/v1/docs-content/jex3-0").param("slugs", "get-started,flow"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.savedSlugs", containsInAnyOrder("get-started", "flow")));
    }

    @Test
    @DisplayName("some requested slugs have a saved row, others do not")
    void someSlugsExist() throws Exception {
        when(content.findSavedSlugs("jex3-0", List.of("get-started", "flow", "faq")))
                .thenReturn(Set.of("get-started"));

        mvc.perform(get("/api/v1/docs-content/jex3-0").param("slugs", "get-started,flow,faq"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.savedSlugs", contains("get-started")));
    }

    @Test
    @DisplayName("none of the requested slugs have a saved row — 200, not the 404 the single-slug route gives")
    void noSlugsExist() throws Exception {
        when(content.findSavedSlugs("jex3-0", List.of("flow", "faq"))).thenReturn(Set.of());

        mvc.perform(get("/api/v1/docs-content/jex3-0").param("slugs", "flow,faq"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.savedSlugs").isArray())
                .andExpect(jsonPath("$.savedSlugs").isEmpty());
    }

    @Test
    @DisplayName("a missing slugs parameter is treated as no candidates, not a 400")
    void missingSlugsParameter() throws Exception {
        mvc.perform(get("/api/v1/docs-content/jex3-0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.savedSlugs").isEmpty());

        verify(content).findSavedSlugs(eq("jex3-0"), eq(List.of()));
    }

    @Test
    @DisplayName("a blank slugs parameter is treated as no candidates, not a 400")
    void blankSlugsParameter() throws Exception {
        mvc.perform(get("/api/v1/docs-content/jex3-0").param("slugs", ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.savedSlugs").isEmpty());

        verify(content).findSavedSlugs(eq("jex3-0"), eq(List.of()));
    }

    @Test
    @DisplayName("blank entries, surrounding whitespace, and duplicates are cleaned up before reaching the service")
    void cleansUpMessyInput() throws Exception {
        mvc.perform(get("/api/v1/docs-content/jex3-0").param("slugs", "flow,,flow, faq ,"))
                .andExpect(status().isOk());

        verify(content).findSavedSlugs(eq("jex3-0"), eq(List.of("flow", "faq")));
    }
}
