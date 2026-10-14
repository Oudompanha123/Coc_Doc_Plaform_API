package com.coc.docs.legal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.coc.docs.PostgresTestBase;
import com.coc.docs.account.Account;
import com.coc.docs.account.AccountRepository;
import com.coc.docs.account.AccountStatus;
import com.coc.docs.account.AppRole;
import com.coc.docs.auth.CurrentUser;
import com.coc.docs.page.VersionStatus;
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
 * The Legal pages (Terms of Service / Privacy Policy) editorial write path,
 * against a real PostgreSQL — the {@code legal_page_version} analogue of
 * {@code PageServiceIntegrationTest}.
 *
 * <p>Terms and Privacy share one shape since V81 — title, byline and one
 * Markdown body ({@link LegalDocumentValidator}). {@code termsContent} and
 * {@code privacyContent} build the same shape; the two names only say which
 * slug a test exercises.
 */
class LegalPageServiceIntegrationTest extends PostgresTestBase {

    @Autowired LegalPageService legal;
    @Autowired LegalPageVersionRepository versions;
    @Autowired AccountRepository accounts;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper json;

    private CurrentUser editor;

    @BeforeEach
    void setUp() {
        // Truncated rather than rolled back, same reasoning as
        // PageServiceIntegrationTest: the publish path's interaction with the
        // partial unique indexes only appears at commit. legal_page_version
        // starts empty here (the migration's own seed rows are wiped too),
        // so every test builds whatever state it needs through the service.
        jdbc.sql("truncate audit_log, legal_page_version, account restart identity cascade").update();

        Account account = accounts.insert(
                "editor@example.com", "Editor", AppRole.SUPER_ADMIN, null, AccountStatus.ACTIVE);
        editor = new CurrentUser(account, accounts.findPermissions(account.id()), new byte[] {1});
    }

    /** The one legal-page shape (V81): sections are the body's `##` headings. */
    private JsonNode termsContent(String title) {
        return json.createObjectNode()
                .put("title", title)
                .put("subtitle", "Coc Docs Platform · Legal Information")
                .put("lastUpdated", "2026-10-01")
                .put("updatedBy", "the Platform Team")
                .put("body", "## Section One\n\nsome paragraph text");
    }

    private JsonNode privacyContent(String title) {
        return termsContent(title);
    }

    // -----------------------------------------------------------------------

    @Test
    @DisplayName("opening for edit creates an empty draft when nothing exists yet")
    void open_for_edit_creates_an_empty_draft() {
        LegalPageVersion draft = legal.openForEdit(LegalSlug.TERMS);

        assertThat(draft.status()).isEqualTo(VersionStatus.DRAFT);
        assertThat(draft.versionNo()).isEqualTo(1);
        assertThat(versions.findDraft(LegalSlug.TERMS)).isPresent();
    }

    @Test
    @DisplayName("a draft save leaves the published page untouched")
    void draft_save_does_not_affect_readers() {
        legal.openForEdit(LegalSlug.TERMS);
        legal.saveDraft(LegalSlug.TERMS, termsContent("Published Title"), 1, editor);
        legal.publish(LegalSlug.TERMS, editor);

        LegalPageVersion draft = legal.openForEdit(LegalSlug.TERMS);
        legal.saveDraft(LegalSlug.TERMS, termsContent("Draft Rewrite"), draft.versionNo(), editor);

        LegalPageVersion published = legal.readPublished(LegalSlug.TERMS);
        assertThat(published.content().get("title").asText()).isEqualTo("Published Title");
    }

    @Test
    @DisplayName("a concurrent save is refused, never silently applied")
    void concurrent_save_is_refused() {
        LegalPageVersion opened = legal.openForEdit(LegalSlug.PRIVACY);
        int versionEveryoneLoaded = opened.versionNo();

        legal.saveDraft(LegalSlug.PRIVACY, privacyContent("First"), versionEveryoneLoaded, editor);

        assertThatThrownBy(() -> legal.saveDraft(
                        LegalSlug.PRIVACY, privacyContent("Second"), versionEveryoneLoaded, editor))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.CONFLICT))
                .hasMessageContaining("changed by someone else")
                .hasMessageContaining("Reload");

        assertThat(versions.findDraft(LegalSlug.PRIVACY))
                .get()
                .satisfies(draft -> assertThat(draft.content().get("title").asText()).isEqualTo("First"));
    }

    @Test
    @DisplayName("publishing archives the previous published row rather than overwriting it")
    void publish_preserves_history() {
        legal.openForEdit(LegalSlug.TERMS);
        legal.saveDraft(LegalSlug.TERMS, termsContent("V1"), 1, editor);
        LegalPageVersion first = legal.publish(LegalSlug.TERMS, editor);

        LegalPageVersion second = legal.openForEdit(LegalSlug.TERMS);
        legal.saveDraft(LegalSlug.TERMS, termsContent("V2"), second.versionNo(), editor);
        LegalPageVersion published = legal.publish(LegalSlug.TERMS, editor);

        assertThat(published.id()).isNotEqualTo(first.id());
        assertThat(published.publishedAt()).isNotNull();
        assertThat(published.publishedBy()).isEqualTo(editor.id());

        assertThat(legal.history(LegalSlug.TERMS))
                .hasSize(2)
                .anySatisfy(v -> assertThat(v.status()).isEqualTo(VersionStatus.ARCHIVED))
                .anySatisfy(v -> assertThat(v.status()).isEqualTo(VersionStatus.PUBLISHED));
    }

    @Test
    @DisplayName("publishing with a note records it on the published row")
    void publish_records_the_note() {
        legal.openForEdit(LegalSlug.TERMS);
        legal.saveDraft(LegalSlug.TERMS, termsContent("V1"), 1, editor);

        LegalPageVersion published = legal.publish(LegalSlug.TERMS, "Updated data retention section", editor);

        assertThat(published.publishNote()).isEqualTo("Updated data retention section");
        assertThat(legal.history(LegalSlug.TERMS))
                .anySatisfy(v -> assertThat(v.publishNote()).isEqualTo("Updated data retention section"));
    }

    @Test
    @DisplayName("publishing with no note leaves it null")
    void publish_with_no_note_is_null() {
        legal.openForEdit(LegalSlug.TERMS);
        legal.saveDraft(LegalSlug.TERMS, termsContent("V1"), 1, editor);

        LegalPageVersion published = legal.publish(LegalSlug.TERMS, editor);

        assertThat(published.publishNote()).isNull();
    }

    @Test
    @DisplayName("the database allows only one published row per slug")
    void one_published_row_per_slug_is_enforced_by_the_database() {
        legal.openForEdit(LegalSlug.TERMS);
        legal.saveDraft(LegalSlug.TERMS, termsContent("Only"), 1, editor);
        legal.publish(LegalSlug.TERMS, editor);

        // Bypassing the service on purpose: the point is that the rule holds
        // even when application code is wrong, which is why it is an index
        // rather than a check in Java.
        assertThatThrownBy(() -> jdbc.sql("""
                                insert into legal_page_version (slug, status, content)
                                values ('terms', 'published', '{}'::jsonb)
                                """)
                        .update())
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("content that fails validation never reaches the database")
    void invalid_content_is_refused_before_persistence() {
        legal.openForEdit(LegalSlug.TERMS);

        // No body at all — required by LegalDocumentValidator.
        var noBody = json.createObjectNode()
                .put("title", "Terms")
                .put("lastUpdated", "2026-10-01")
                .put("updatedBy", "the Platform Team")
                .put("body", "   ");

        assertThatThrownBy(() -> legal.saveDraft(LegalSlug.TERMS, noBody, 1, editor))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status())
                        .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY))
                .satisfies(e -> assertThat(((ApiException) e).issues()).isNotEmpty());

        // The draft is still the empty one openForEdit created.
        assertThat(versions.findDraft(LegalSlug.TERMS))
                .get()
                .satisfies(draft -> assertThat(draft.content().has("title")).isFalse());
    }

    @Test
    @DisplayName("publishing re-validates — a draft saved under looser rules cannot slip through")
    void publish_revalidates_content() {
        legal.openForEdit(LegalSlug.TERMS);
        legal.saveDraft(LegalSlug.TERMS, termsContent("Valid for now"), 1, editor);

        // Simulate a draft that predates a tightened rule by writing directly,
        // bypassing saveDraft's own validation.
        LegalPageVersion draft = versions.findDraft(LegalSlug.TERMS).orElseThrow();
        versions.updateDraft(draft.id(), draft.versionNo(), json.createObjectNode().put("title", "Bare"));

        assertThatThrownBy(() -> legal.publish(LegalSlug.TERMS, editor))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status())
                        .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
    }

    @Test
    @DisplayName("restore copies an old version's content into the draft, without publishing")
    void restore_copies_content_into_draft_only() {
        legal.openForEdit(LegalSlug.TERMS);
        legal.saveDraft(LegalSlug.TERMS, termsContent("Original"), 1, editor);
        LegalPageVersion original = legal.publish(LegalSlug.TERMS, editor);

        LegalPageVersion newDraft = legal.openForEdit(LegalSlug.TERMS);
        legal.saveDraft(LegalSlug.TERMS, termsContent("Replacement"), newDraft.versionNo(), editor);
        legal.publish(LegalSlug.TERMS, editor);

        legal.restore(LegalSlug.TERMS, original.id(), editor);

        // Restored into the draft...
        assertThat(versions.findDraft(LegalSlug.TERMS))
                .get()
                .satisfies(d -> assertThat(d.content().get("title").asText()).isEqualTo("Original"));

        // ...but the live published page is still "Replacement" — restore
        // never publishes by itself.
        assertThat(legal.readPublished(LegalSlug.TERMS).content().get("title").asText())
                .isEqualTo("Replacement");
    }

    @Test
    @DisplayName("a historical version can be previewed by id without touching the draft")
    void get_history_version_never_touches_the_draft() {
        legal.openForEdit(LegalSlug.TERMS);
        legal.saveDraft(LegalSlug.TERMS, termsContent("Original"), 1, editor);
        LegalPageVersion original = legal.publish(LegalSlug.TERMS, editor);

        LegalPageVersion newDraft = legal.openForEdit(LegalSlug.TERMS);
        legal.saveDraft(LegalSlug.TERMS, termsContent("Still editing"), newDraft.versionNo(), editor);

        LegalPageVersion fetched = legal.getHistoryVersion(LegalSlug.TERMS, original.id());

        assertThat(fetched.content().get("title").asText()).isEqualTo("Original");
        assertThat(versions.findDraft(LegalSlug.TERMS))
                .get()
                .satisfies(d -> assertThat(d.content().get("title").asText()).isEqualTo("Still editing"));
    }

    @Test
    @DisplayName("a version from another slug is not reachable through this one")
    void get_history_version_is_scoped_to_its_own_slug() {
        legal.openForEdit(LegalSlug.TERMS);
        legal.saveDraft(LegalSlug.TERMS, termsContent("Terms V1"), 1, editor);
        LegalPageVersion termsVersion = legal.publish(LegalSlug.TERMS, editor);

        assertThatThrownBy(() -> legal.getHistoryVersion(LegalSlug.PRIVACY, termsVersion.id()))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    @DisplayName("every editorial action writes an audit row")
    void the_audit_trail_records_saves_publishes_and_restores() {
        legal.openForEdit(LegalSlug.TERMS);
        legal.saveDraft(LegalSlug.TERMS, termsContent("V1"), 1, editor);
        LegalPageVersion first = legal.publish(LegalSlug.TERMS, editor);

        LegalPageVersion draft = legal.openForEdit(LegalSlug.TERMS);
        legal.saveDraft(LegalSlug.TERMS, termsContent("V2"), draft.versionNo(), editor);
        legal.publish(LegalSlug.TERMS, editor);
        legal.restore(LegalSlug.TERMS, first.id(), editor);

        var actions = jdbc.sql("select action from audit_log order by id").query(String.class).list();

        assertThat(actions).contains("legal.draft_save", "legal.publish", "legal.restore");
        assertThat(jdbc.sql("select distinct actor_email from audit_log").query(String.class).list())
                .containsExactly("editor@example.com");
    }

    @Test
    @DisplayName("reading before anything is published is a 404")
    void reading_unpublished_slug_is_not_found() {
        assertThatThrownBy(() -> legal.readPublished(LegalSlug.PRIVACY))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.NOT_FOUND));
    }
}
