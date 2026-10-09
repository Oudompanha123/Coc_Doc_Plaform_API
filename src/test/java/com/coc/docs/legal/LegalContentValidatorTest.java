package com.coc.docs.legal;

import static org.assertj.core.api.Assertions.assertThat;

import com.coc.docs.content.ValidationResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for the authoritative Legal page content validator — the
 * server-side check a Legal page save/publish actually enforces, the
 * frontend's own equivalent being advisory only (ADR-012 §3). Each
 * section's body and the plain-language box are plain Markdown strings;
 * the whitelist/HTML/link rules for those are covered in {@link
 * MarkdownValidatorTest} — this class only checks that the envelope wires
 * that validator in at the right paths with the right length limits.
 */
class LegalContentValidatorTest {

    private final ObjectMapper json = new ObjectMapper();
    private final LegalContentValidator validator = new LegalContentValidator(new MarkdownValidator());

    private JsonNode content(String raw) {
        try {
            return json.readTree(raw);
        } catch (Exception e) {
            throw new AssertionError("the test fixture itself is not valid JSON", e);
        }
    }

    private ValidationResult validate(String raw) {
        return validator.validate(content(raw));
    }

    private static final String VALID = """
            {
              "title": "Terms of Service",
              "lead": "The simple rules.",
              "eyebrow": "Coc Docs Platform · Legal",
              "lastUpdated": "2026-10-01",
              "status": "draft",
              "bulletStyle": "circle",
              "highlights": [
                {"icon": "person", "title": "Your account", "text": "Keep it safe."},
                {"icon": "document", "title": "Your work", "text": "For your team only."},
                {"icon": "ban", "title": "Fair use", "text": "Stay in your role."}
              ],
              "plainLanguage": "The short version.",
              "sections": [
                {"id": "s1", "title": "Section One", "icon": "document", "body": "Some text."},
                {"id": "s2", "title": "Section Two", "icon": "person", "body": "- one\\n- two"}
              ],
              "cta": {"title": "Have a question?", "text": "We are happy to help."}
            }
            """;

    @Test
    @DisplayName("a well-formed page passes")
    void a_valid_page_passes() {
        ValidationResult result = validate(VALID);
        assertThat(result.valid()).withFailMessage(() -> result.issues().toString()).isTrue();
    }

    @Nested
    @DisplayName("the envelope")
    class Envelope {

        @Test
        void a_non_object_is_rejected() {
            ValidationResult result = validate("[]");
            assertThat(result.valid()).isFalse();
            assertThat(result.issues()).anyMatch(i -> i.path().equals("content"));
        }

        @Test
        void a_null_content_is_rejected_without_throwing() {
            ValidationResult result = validator.validate(null);
            assertThat(result.valid()).isFalse();
        }
    }

    @Nested
    @DisplayName("page details")
    class PageDetails {

        @Test
        void a_blank_title_is_rejected() {
            String raw = VALID.replaceFirst("\"title\": \"Terms of Service\"", "\"title\": \"\"");
            assertThat(validate(raw).valid()).isFalse();
        }

        @Test
        void a_title_over_80_characters_is_rejected() {
            String tooLong = "x".repeat(81);
            String raw = VALID.replaceFirst(
                    "\"title\": \"Terms of Service\"", "\"title\": \"" + tooLong + "\"");
            assertThat(validate(raw).valid()).isFalse();
        }

        @Test
        void a_title_at_exactly_80_characters_passes() {
            String exact = "x".repeat(80);
            String raw = VALID.replaceFirst(
                    "\"title\": \"Terms of Service\"", "\"title\": \"" + exact + "\"");
            assertThat(validate(raw).valid()).isTrue();
        }

        @Test
        void an_eyebrow_over_40_characters_is_rejected() {
            String tooLong = "x".repeat(41);
            String raw = VALID.replace("\"eyebrow\": \"Coc Docs Platform · Legal\"", "\"eyebrow\": \"" + tooLong + "\"");
            assertThat(validate(raw).valid()).isFalse();
        }

        @Test
        void a_missing_eyebrow_is_fine_its_optional() {
            String raw = VALID.replace("\"eyebrow\": \"Coc Docs Platform · Legal\",", "");
            assertThat(validate(raw).valid()).isTrue();
        }

        @Test
        void a_lead_over_160_characters_is_rejected() {
            String tooLong = "x".repeat(161);
            String raw = VALID.replaceFirst(
                    "\"lead\": \"The simple rules.\"", "\"lead\": \"" + tooLong + "\"");
            assertThat(validate(raw).valid()).isFalse();
        }

        @Test
        void a_missing_lastUpdated_is_rejected() {
            String raw = VALID.replace("\"lastUpdated\": \"2026-10-01\",", "");
            assertThat(validate(raw).valid()).isFalse();
        }

        @Test
        void an_unknown_status_is_rejected() {
            String raw = VALID.replaceFirst("\"status\": \"draft\"", "\"status\": \"published\"");
            assertThat(validate(raw).valid()).isFalse();
        }

        @Test
        void approved_status_is_valid() {
            String raw = VALID.replaceFirst("\"status\": \"draft\"", "\"status\": \"approved\"");
            assertThat(validate(raw).valid()).isTrue();
        }

        @Test
        void an_unknown_bullet_style_is_rejected() {
            String raw = VALID.replaceFirst("\"bulletStyle\": \"circle\"", "\"bulletStyle\": \"square\"");
            assertThat(validate(raw).valid()).isFalse();
        }

        @Test
        void check_bullet_style_is_valid() {
            String raw = VALID.replaceFirst("\"bulletStyle\": \"circle\"", "\"bulletStyle\": \"check\"");
            assertThat(validate(raw).valid()).isTrue();
        }
    }

    @Nested
    @DisplayName("highlight cards")
    class Highlights {

        @Test
        void exactly_three_are_required_not_two() {
            String raw = VALID.replaceFirst(
                    "(?s)\"highlights\": \\[.*?\\],\\s*\"plainLanguage\"",
                    "\"highlights\": [{\"icon\": \"person\", \"title\": \"A\", \"text\": \"a\"}], \"plainLanguage\"");
            assertThat(validate(raw).valid()).isFalse();
        }

        @Test
        void a_highlight_title_over_40_characters_is_rejected() {
            String tooLong = "x".repeat(41);
            String raw = VALID.replaceFirst(
                    "\"title\": \"Your account\"", "\"title\": \"" + tooLong + "\"");
            assertThat(validate(raw).valid()).isFalse();
        }

        @Test
        void a_highlight_text_over_120_characters_is_rejected() {
            String tooLong = "x".repeat(121);
            String raw = VALID.replaceFirst("\"text\": \"Keep it safe.\"", "\"text\": \"" + tooLong + "\"");
            assertThat(validate(raw).valid()).isFalse();
        }

        @Test
        void a_missing_icon_is_rejected() {
            String raw = VALID.replaceFirst("\"icon\": \"person\", ", "");
            assertThat(validate(raw).valid()).isFalse();
        }
    }

    @Nested
    @DisplayName("plain language")
    class PlainLanguage {

        @Test
        void an_empty_string_is_rejected() {
            String raw = VALID.replaceFirst("\"plainLanguage\": \"The short version.\"", "\"plainLanguage\": \"\"");
            assertThat(validate(raw).valid()).isFalse();
        }

        @Test
        void over_600_characters_is_rejected() {
            String tooLong = "x".repeat(601);
            String raw = VALID.replaceFirst(
                    "\"plainLanguage\": \"The short version.\"", "\"plainLanguage\": \"" + tooLong + "\"");
            assertThat(validate(raw).valid()).isFalse();
        }

        @Test
        void raw_html_is_rejected() {
            String raw = VALID.replaceFirst(
                    "\"plainLanguage\": \"The short version.\"", "\"plainLanguage\": \"<script>alert(1)</script>\"");
            assertThat(validate(raw).valid()).isFalse();
        }
    }

    @Nested
    @DisplayName("cta")
    class Cta {

        @Test
        void a_missing_cta_title_is_rejected() {
            String raw = VALID.replaceFirst("\"title\": \"Have a question\\?\", ", "");
            assertThat(validate(raw).valid()).isFalse();
        }

        @Test
        void a_cta_title_over_40_characters_is_rejected() {
            String tooLong = "x".repeat(41);
            String raw = VALID.replaceFirst(
                    "\"title\": \"Have a question\\?\"", "\"title\": \"" + tooLong + "\"");
            assertThat(validate(raw).valid()).isFalse();
        }
    }

    @Nested
    @DisplayName("sections")
    class Sections {

        @Test
        void zero_sections_is_rejected() {
            String raw = VALID.replaceFirst(
                    "(?s)\"sections\": \\[.*\\],\\s*\"cta\"", "\"sections\": [], \"cta\"");
            assertThat(validate(raw).valid()).isFalse();
        }

        @Test
        void duplicate_anchor_ids_are_rejected() {
            String raw = VALID.replaceFirst("\"id\": \"s2\"", "\"id\": \"s1\"");
            assertThat(validate(raw).valid()).isFalse();
        }

        @Test
        void a_section_title_over_80_characters_is_rejected() {
            String tooLong = "x".repeat(81);
            String raw = VALID.replaceFirst(
                    "\"title\": \"Section One\"", "\"title\": \"" + tooLong + "\"");
            assertThat(validate(raw).valid()).isFalse();
        }

        @Test
        void a_missing_icon_is_rejected() {
            String raw = VALID.replaceFirst("\"icon\": \"document\", \"body\"", "\"body\"");
            assertThat(validate(raw).valid()).isFalse();
        }

        @Test
        void an_empty_body_is_rejected() {
            String raw = VALID.replaceFirst("\"body\": \"Some text\\.\"", "\"body\": \"\"");
            assertThat(validate(raw).valid()).isFalse();
        }

        @Test
        void a_body_with_raw_html_is_rejected() {
            String raw = VALID.replaceFirst(
                    "\"body\": \"Some text\\.\"", "\"body\": \"plain <b>bold</b> text\"");
            assertThat(validate(raw).valid()).isFalse();
        }

        @Test
        void a_body_with_a_javascript_link_is_rejected() {
            String raw = VALID.replaceFirst(
                    "\"body\": \"Some text\\.\"", "\"body\": \"[click](javascript:alert(1))\"");
            assertThat(validate(raw).valid()).isFalse();
        }

        @Test
        void a_body_with_an_https_link_passes() {
            String raw = VALID.replaceFirst(
                    "\"body\": \"Some text\\.\"", "\"body\": \"[click](https://example.com)\"");
            assertThat(validate(raw).valid()).isTrue();
        }

        @Test
        void a_body_over_2000_characters_is_rejected() {
            String tooLong = "x".repeat(2001);
            String raw = VALID.replaceFirst("\"body\": \"Some text\\.\"", "\"body\": \"" + tooLong + "\"");
            assertThat(validate(raw).valid()).isFalse();
        }

        @Test
        void more_than_fifteen_sections_is_rejected() {
            StringBuilder sections = new StringBuilder();
            for (int i = 0; i < 16; i++) {
                if (i > 0) {
                    sections.append(",");
                }
                sections.append(
                        "{\"id\": \"s" + i + "\", \"title\": \"Section " + i + "\", \"icon\": \"document\", \"body\": \"text\"}");
            }
            String raw = VALID.replaceFirst(
                    "(?s)\"sections\": \\[.*\\],\\s*\"cta\"", "\"sections\": [" + sections + "], \"cta\"");
            assertThat(validate(raw).valid()).isFalse();
        }
    }
}
