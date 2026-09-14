package com.coc.docs.page;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.coc.docs.PostgresTestBase;
import com.coc.docs.account.Account;
import com.coc.docs.account.AccountRepository;
import com.coc.docs.account.AccountStatus;
import com.coc.docs.account.AppRole;
import com.coc.docs.auth.CurrentUser;
import com.coc.docs.content.Locale;
import com.coc.docs.section.Section;
import com.coc.docs.section.SectionRepository;
import com.coc.docs.web.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The editorial write path, against a real PostgreSQL.
 *
 * <p>These are the Sprint 1 T9 exit criteria as assertions: a draft save leaves
 * the published page untouched, and a concurrent save is refused rather than
 * silently overwritten.
 *
 * <p>// req: FR-CONT-003, FR-CONT-005, FR-CONT-012, FR-CONT-013
 */
class PageServiceIntegrationTest extends PostgresTestBase {

    @Autowired PageService content;
    @Autowired PageRepository pages;
    @Autowired PageVersionRepository versions;
    @Autowired SectionRepository sections;
    @Autowired AccountRepository accounts;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper json;

    private CurrentUser editor;
    private Section section;
    private Page page;

    @BeforeEach
    void setUp() {
        // Truncated rather than rolled back: the publish path spans several
        // statements whose interaction with the partial unique indexes is the
        // thing under test, and a rollback-per-test wrapper would hide an index
        // violation that only appears at commit.
        jdbc.sql("truncate audit_log, app_session, page_version, page_slug_history, page, "
                        + "section, account restart identity cascade")
                .update();

        Account account = accounts.insert(
                "editor@example.com", "Editor", AppRole.ADMIN, null, AccountStatus.ACTIVE);
        editor = new CurrentUser(account, accounts.findPermissions(account.id()), new byte[] {1});

        section = sections.insert("jex2.0", 2, true);
        page = pages.insert(section.id(), "usage", 1, null, AppRole.USER);
    }

    private JsonNode body(String text) {
        return json.createArrayNode()
                .add(json.createObjectNode()
                        .put("type", "paragraph")
                        .set("text", json.createArrayNode()
                                .add(json.createObjectNode().put("text", text))));
    }

    /** A caller of the given role, backed by a real account row. */
    private CurrentUser caller(AppRole role) {
        Account account = accounts.insert(
                role.wire() + "@example.com", "Test", role, null, AccountStatus.ACTIVE);
        return new CurrentUser(account, accounts.findPermissions(account.id()), new byte[] {2});
    }

    /**
     * Publishes something at the given page, so reads have a target.
     *
     * <p>Takes the actor because the editorial path is itself gated by
     * {@code min_role} now: seeding a {@code super_admin} page needs a
     * {@code super_admin}.
     */
    private void publishTo(Page target, String title, CurrentUser actor) {
        content.openForEdit(target.id(), Locale.EN, actor);
        content.saveDraft(target.id(), Locale.EN, title, "", body("x"), 1, actor);
        content.publish(target.id(), Locale.EN, actor);
    }

    // -----------------------------------------------------------------------

    @Test
    @DisplayName("a draft save leaves the published page untouched")
    void draft_save_does_not_affect_readers() {
        // Publish something a reader can see.
        content.openForEdit(page.id(), Locale.EN, editor);
        content.saveDraft(page.id(), Locale.EN, "Usage", "", body("published text"), 1, editor);
        content.publish(page.id(), Locale.EN, editor);

        // Now edit it again without publishing.
        PageVersion draft = content.openForEdit(page.id(), Locale.EN, editor);
        content.saveDraft(
                page.id(), Locale.EN, "Usage rewritten", "", body("draft text"),
                draft.versionNo(), editor);

        // This is the property that makes draft and published separate rows
        // rather than a flag: a reader sees no trace of the edit.
        PageService.PageLookup reader = content.readPublished("jex2.0", "usage", Locale.EN, editor);
        assertThat(reader.isMoved()).isFalse();
        assertThat(reader.found().version().title()).isEqualTo("Usage");
        assertThat(reader.found().version().body().toString()).contains("published text");
    }

    @Test
    @DisplayName("a concurrent save is refused, never silently applied")
    void concurrent_save_is_refused() {
        PageVersion opened = content.openForEdit(page.id(), Locale.EN, editor);
        int versionEveryoneLoaded = opened.versionNo();

        // First editor saves and moves version_no forward.
        content.saveDraft(
                page.id(), Locale.EN, "First", "", body("first"), versionEveryoneLoaded, editor);

        // Second editor still holds the number from before that save.
        assertThatThrownBy(() -> content.saveDraft(
                        page.id(), Locale.EN, "Second", "", body("second"),
                        versionEveryoneLoaded, editor))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status())
                        .isEqualTo(HttpStatus.CONFLICT))
                // The message has to be actionable: an editor who loses work to a
                // colleague needs to know that is what happened.
                .hasMessageContaining("changed by someone else")
                .hasMessageContaining("Reload");

        // And the first editor's work survived.
        assertThat(versions.findDraft(page.id(), Locale.EN))
                .get()
                .satisfies(draft -> assertThat(draft.title()).isEqualTo("First"));
    }

    @Test
    @DisplayName("publishing archives the previous published row rather than overwriting it")
    void publish_preserves_history() {
        content.openForEdit(page.id(), Locale.EN, editor);
        content.saveDraft(page.id(), Locale.EN, "First", "", body("v1"), 1, editor);
        PageVersion first = content.publish(page.id(), Locale.EN, editor);

        PageVersion second = content.openForEdit(page.id(), Locale.EN, editor);
        content.saveDraft(
                page.id(), Locale.EN, "Second", "", body("v2"), second.versionNo(), editor);
        PageVersion published = content.publish(page.id(), Locale.EN, editor);

        assertThat(published.id()).isNotEqualTo(first.id());

        // The old row is still there, which is what makes FR-CONT-012 free
        // rather than a feature someone has to build.
        assertThat(content.history(page.id(), Locale.EN, editor))
                .hasSize(2)
                .anySatisfy(v -> assertThat(v.status()).isEqualTo(VersionStatus.ARCHIVED))
                .anySatisfy(v -> assertThat(v.status()).isEqualTo(VersionStatus.PUBLISHED));
    }

    @Test
    @DisplayName("the database allows only one published row per page and locale")
    void one_published_row_per_locale_is_enforced_by_the_database() {
        content.openForEdit(page.id(), Locale.EN, editor);
        content.saveDraft(page.id(), Locale.EN, "Only", "", body("x"), 1, editor);
        content.publish(page.id(), Locale.EN, editor);

        // Bypassing the service on purpose: the point is that the rule holds even
        // when application code is wrong, which is the reason it is an index
        // rather than a check in Java.
        assertThatThrownBy(() -> jdbc.sql("""
                                insert into page_version (page_id, locale, status, title, body)
                                values (?, 'en'::locale, 'published', 'Second', '[]'::jsonb)
                                """)
                        .param(page.id())
                        .update())
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("a body that fails the validator never reaches the database")
    void an_invalid_body_is_refused_before_persistence() {
        content.openForEdit(page.id(), Locale.EN, editor);

        JsonNode hostile = json.createArrayNode()
                .add(json.createObjectNode()
                        .put("type", "paragraph")
                        .set("text", json.createArrayNode()
                                .add(json.createObjectNode()
                                        .put("text", "click me")
                                        .set("link", json.createObjectNode()
                                                .put("href", "javascript:alert(1)")))));

        assertThatThrownBy(() -> content.saveDraft(
                        page.id(), Locale.EN, "Hostile", "", hostile, 1, editor))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status())
                        .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY))
                .satisfies(e -> assertThat(((ApiException) e).issues()).isNotEmpty());

        // The draft is still the empty one openForEdit created.
        assertThat(versions.findDraft(page.id(), Locale.EN))
                .get()
                .satisfies(draft -> assertThat(draft.body().toString()).doesNotContain("javascript"));
    }

    @Test
    @DisplayName("a translation records the en version it was made from")
    void translation_staleness_is_derived_not_flagged() {
        content.openForEdit(page.id(), Locale.EN, editor);
        content.saveDraft(page.id(), Locale.EN, "English", "", body("en v1"), 1, editor);
        PageVersion enV1 = content.publish(page.id(), Locale.EN, editor);

        content.openForEdit(page.id(), Locale.KM, editor);
        content.saveDraft(page.id(), Locale.KM, "Khmer", "", body("km"), 1, editor);
        PageVersion km = content.publish(page.id(), Locale.KM, editor);

        assertThat(km.sourceVersionId()).isEqualTo(enV1.id());

        // Nothing is stale yet.
        assertThat(content.translationStatus("jex2.0"))
                .filteredOn(row -> row.locale().equals("km"))
                .allSatisfy(row -> {
                    assertThat(row.existsPublished()).isTrue();
                    assertThat(row.stale()).isFalse();
                });

        // Move English on. The Khmer row is now stale, and nobody set a flag.
        PageVersion next = content.openForEdit(page.id(), Locale.EN, editor);
        content.saveDraft(
                page.id(), Locale.EN, "English v2", "", body("en v2"), next.versionNo(), editor);
        content.publish(page.id(), Locale.EN, editor);

        assertThat(content.translationStatus("jex2.0"))
                .filteredOn(row -> row.locale().equals("km"))
                .allSatisfy(row -> assertThat(row.stale()).isTrue());

        // Korean was never translated. Missing is not the same as stale, and
        // conflating them was AMB-002.
        assertThat(content.translationStatus("jex2.0"))
                .filteredOn(row -> row.locale().equals("ko"))
                .allSatisfy(row -> {
                    assertThat(row.existsPublished()).isFalse();
                    assertThat(row.stale()).isFalse();
                });
    }

    @Test
    @DisplayName("a renamed slug redirects instead of 404ing")
    void a_renamed_slug_resolves_to_the_new_one() {
        content.openForEdit(page.id(), Locale.EN, editor);
        content.saveDraft(page.id(), Locale.EN, "Usage", "", body("x"), 1, editor);
        content.publish(page.id(), Locale.EN, editor);

        pages.rename(page, "getting-started");

        PageService.PageLookup lookup = content.readPublished("jex2.0", "usage", Locale.EN, editor);

        // The whole reason page_slug_history exists: an inbound bookmark or an
        // external link keeps working, so renaming stays something people dare do.
        assertThat(lookup.isMoved()).isTrue();
        assertThat(lookup.moved().canonicalSlug()).isEqualTo("getting-started");
    }

    @Test
    @DisplayName("a page cannot be deleted while other pages link to it")
    void delete_is_refused_while_inbound_links_exist() {
        Page other = pages.insert(section.id(), "overview", 2, null, AppRole.USER);

        JsonNode linking = json.createArrayNode()
                .add(json.createObjectNode()
                        .put("type", "paragraph")
                        .set("text", json.createArrayNode()
                                .add(json.createObjectNode()
                                        .put("text", "see usage")
                                        .set("link", json.createObjectNode()
                                                .put("pageId", page.id().toString())))));

        content.openForEdit(other.id(), Locale.EN, editor);
        content.saveDraft(other.id(), Locale.EN, "Overview", "", linking, 1, editor);
        content.publish(other.id(), Locale.EN, editor);

        assertThatThrownBy(() -> content.delete(page.id(), editor))
                .isInstanceOf(ApiException.class)
                // Naming the referring page is the value of storing references
                // rather than URLs: the answer is exact, not a text search.
                .hasMessageContaining("overview");

        assertThat(pages.findById(page.id())).isPresent();
    }

    @Test
    @DisplayName("a page with no published version in a locale is a 404, not an empty page")
    void a_missing_translation_is_not_served_as_an_empty_page() {
        content.openForEdit(page.id(), Locale.EN, editor);
        content.saveDraft(page.id(), Locale.EN, "Usage", "", body("x"), 1, editor);
        content.publish(page.id(), Locale.EN, editor);

        assertThatThrownBy(() -> content.readPublished("jex2.0", "usage", Locale.KO, editor))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status())
                        .isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    @DisplayName("navigation omits pages with nothing published in the requested locale")
    void navigation_reflects_published_content_only() {
        Page translated = pages.insert(section.id(), "translated", 1, null, AppRole.USER);
        Page englishOnly = pages.insert(section.id(), "english-only", 2, null, AppRole.USER);

        for (Page p : new Page[] {translated, englishOnly}) {
            content.openForEdit(p.id(), Locale.EN, editor);
            content.saveDraft(p.id(), Locale.EN, "T " + p.slug(), "", body("x"), 1, editor);
            content.publish(p.id(), Locale.EN, editor);
        }
        content.openForEdit(translated.id(), Locale.KM, editor);
        content.saveDraft(translated.id(), Locale.KM, "Khmer", "", body("x"), 1, editor);
        content.publish(translated.id(), Locale.KM, editor);

        // A nav link to a page with nothing published in the reader's language
        // would render an empty page, which is worse than the link being absent.
        // Coverage gaps are reported through translation_status instead.
        assertThat(content.navigation("jex2.0", Locale.KM, editor))
                .extracting(PageRepository.NavEntry::slug)
                .containsExactly("translated");

        assertThat(content.navigation("jex2.0", Locale.EN, editor))
                .extracting(PageRepository.NavEntry::slug)
                .contains("translated", "english-only");
    }

    // -----------------------------------------------------------------------
    // FR-AUTH-006 — page.min_role, enforced server-side
    //
    // These are the direct-call tests: they invoke the service, so they pass or
    // fail regardless of what any UI does. A rule that only holds when the
    // frontend cooperates is not a rule (RISK-019).
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("a page above the caller's role is refused, not served")
    void min_role_is_enforced_on_the_reader_path() {
        Page restricted = pages.insert(section.id(), "internal", 9, null, AppRole.ADMIN);
        publishTo(restricted, "Internal", editor);

        CurrentUser reader = caller(AppRole.USER);

        assertThatThrownBy(() -> content.readPublished("jex2.0", "internal", Locale.EN, reader))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status())
                        .isEqualTo(HttpStatus.FORBIDDEN));

        // And the same call by someone who is allowed still works, so the check
        // is a role comparison rather than a blanket refusal.
        assertThat(content.readPublished("jex2.0", "internal", Locale.EN, editor).found())
                .isNotNull();
    }

    @Test
    @DisplayName("navigation omits pages the caller may not open")
    void min_role_is_enforced_in_navigation() {
        Page open = pages.insert(section.id(), "public-ish", 8, null, AppRole.USER);
        Page restricted = pages.insert(section.id(), "internal", 9, null, AppRole.ADMIN);
        publishTo(open, "Open", editor);
        publishTo(restricted, "Internal", editor);

        CurrentUser reader = caller(AppRole.USER);

        // A nav entry carries the slug and the published title. Leaving the
        // restricted entry in would disclose that the page exists and what it is
        // called, while only its body stayed protected.
        assertThat(content.navigation("jex2.0", Locale.EN, reader))
                .extracting(PageRepository.NavEntry::slug)
                .contains("public-ish")
                .doesNotContain("internal");

        assertThat(content.navigation("jex2.0", Locale.EN, editor))
                .extracting(PageRepository.NavEntry::slug)
                .contains("public-ish", "internal");
    }

    @Test
    @DisplayName("an admin cannot read a super_admin page by opening it in the editor")
    void min_role_is_enforced_on_the_editorial_path() {
        Page restricted = pages.insert(section.id(), "board-only", 7, null, AppRole.SUPER_ADMIN);

        // The editor returns the body, so an unguarded editorial path would be a
        // way around the reader check rather than a separate concern.
        assertThatThrownBy(() -> content.openForEdit(restricted.id(), Locale.EN, editor))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status())
                        .isEqualTo(HttpStatus.FORBIDDEN));

        assertThatThrownBy(() -> content.history(restricted.id(), Locale.EN, editor))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status())
                        .isEqualTo(HttpStatus.FORBIDDEN));

        // A super admin is not blocked, which is what makes it a ladder check.
        CurrentUser superAdmin = caller(AppRole.SUPER_ADMIN);
        assertThat(content.openForEdit(restricted.id(), Locale.EN, superAdmin)).isNotNull();
    }

    @Test
    @DisplayName("the rename redirect does not leak a page the caller may not read")
    void min_role_is_enforced_on_the_slug_redirect() {
        Page restricted = pages.insert(section.id(), "old-internal", 6, null, AppRole.ADMIN);
        publishTo(restricted, "Internal", editor);
        pages.rename(restricted, "new-internal");

        CurrentUser reader = caller(AppRole.USER);

        // A 301 names the canonical slug. Answering one here would leak through
        // the redirect exactly what the check on the page itself withholds.
        assertThatThrownBy(() ->
                        content.readPublished("jex2.0", "old-internal", Locale.EN, reader))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status())
                        .isEqualTo(HttpStatus.FORBIDDEN));

        assertThat(content.readPublished("jex2.0", "old-internal", Locale.EN, editor).isMoved())
                .isTrue();
    }

    @Test
    @DisplayName("every editorial action writes an audit row")
    void the_audit_trail_records_saves_and_publishes() {
        content.openForEdit(page.id(), Locale.EN, editor);
        content.saveDraft(page.id(), Locale.EN, "Usage", "", body("x"), 1, editor);
        content.publish(page.id(), Locale.EN, editor);

        var actions = jdbc.sql("select action from audit_log order by id")
                .query(String.class)
                .list();

        assertThat(actions).contains("page.draft_save", "page.publish");

        // The actor email is denormalised so the entry stays readable after the
        // account is deactivated. An audit trail naming "some deleted user" is
        // not an audit trail.
        assertThat(jdbc.sql("select distinct actor_email from audit_log").query(String.class).list())
                .containsExactly("editor@example.com");
    }
}
