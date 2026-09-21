package com.coc.docs.docscontent;

import static org.assertj.core.api.Assertions.assertThat;

import com.coc.docs.PostgresTestBase;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@link DocsContentRepository#findSavedSlugs} against a real PostgreSQL.
 *
 * <p>The dynamic {@code slug in (?, ?, ...)} clause it builds is exactly the
 * kind of thing worth proving against the real driver rather than trusting by
 * inspection — the placeholder count and parameter order have to line up
 * exactly, and a mistake there is invisible to a mocked-service test like
 * {@code DocsContentControllerTest}.
 *
 * <p>// req: ADR-012 investigation, 2026-09-15
 */
class DocsContentRepositoryIntegrationTest extends PostgresTestBase {

    @Autowired DocsContentRepository repository;
    @Autowired JdbcClient jdbc;

    @BeforeEach
    void setUp() {
        jdbc.sql("truncate docs_content").update();
        insertSaved("jex3-0", "get-started");
        insertSaved("jex3-0", "flow");
        // A different product's saved row, so a test that only varies the
        // product key catches this leaking across the product boundary.
        insertSaved("jex2-0", "overview");
    }

    private void insertSaved(String productKey, String slug) {
        jdbc.sql("insert into docs_content (product_key, slug, kind, body) values (?, ?, 'hero', '{}'::jsonb)")
                .params(productKey, slug)
                .update();
    }

    @Test
    @DisplayName("every requested slug has a saved row")
    void allSlugsExist() {
        assertThat(repository.findSavedSlugs("jex3-0", List.of("get-started", "flow")))
                .containsExactlyInAnyOrder("get-started", "flow");
    }

    @Test
    @DisplayName("some requested slugs have a saved row, others do not")
    void someSlugsExist() {
        assertThat(repository.findSavedSlugs("jex3-0", List.of("get-started", "faq")))
                .containsExactly("get-started");
    }

    @Test
    @DisplayName("none of the requested slugs have a saved row")
    void noSlugsExist() {
        assertThat(repository.findSavedSlugs("jex3-0", List.of("faq", "releases"))).isEmpty();
    }

    @Test
    @DisplayName("an empty candidate list is answered without a query, not with the invalid `slug in ()`")
    void emptyCandidateList() {
        assertThat(repository.findSavedSlugs("jex3-0", List.of())).isEmpty();
    }

    @Test
    @DisplayName("a saved row under a different product never counts for this product")
    void scopedToProduct() {
        assertThat(repository.findSavedSlugs("jex3-0", List.of("overview"))).isEmpty();
        assertThat(repository.findSavedSlugs("jex2-0", List.of("overview"))).containsExactly("overview");
    }
}
