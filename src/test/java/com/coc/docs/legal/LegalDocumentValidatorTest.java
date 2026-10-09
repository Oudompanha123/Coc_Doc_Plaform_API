package com.coc.docs.legal;

import static org.assertj.core.api.Assertions.assertThat;

import com.coc.docs.content.ValidationResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for the authoritative Terms-document content validator — mirrors
 * {@code legal-document-validate.ts}'s {@code validateLegalDocument}. The
 * whitelist/HTML/link rules for Markdown fields are covered in {@link
 * MarkdownValidatorTest}; this class only checks the envelope wires that
 * validator in at the right paths with the right length limits.
 */
class LegalDocumentValidatorTest {

    private final ObjectMapper json = new ObjectMapper();
    private final LegalDocumentValidator validator = new LegalDocumentValidator(new MarkdownValidator());

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
              "subtitle": "Coc Docs Platform · Legal Information",
              "status": "draft",
              "lastUpdated": "2026-10-01",
              "updatedBy": "the Platform Team",
              "endNote": "*Subject to change.*",
              "sections": [
                {"id": "intro", "title": "Intro", "body": "Hello there."},
                {"id": "rules", "title": "Rules", "body": "- one\\n- two"}
              ]
            }
            """;

    @Test
    @DisplayName("a fully filled-in document passes")
    void valid_document_passes() {
        assertThat(validate(VALID).valid()).isTrue();
    }

    @Test
    void not_an_object_fails() {
        assertThat(validate("[]").valid()).isFalse();
    }

    @Nested
    @DisplayName("envelope fields")
    class Envelope {
        @Test
        void missing_title_fails() {
            assertThat(validate(VALID.replaceFirst("\"Terms of Service\"", "\"\"")).valid()).isFalse();
        }

        @Test
        void title_over_80_chars_fails() {
            String tooLong = "x".repeat(81);
            assertThat(validate(VALID.replaceFirst("\"Terms of Service\"", "\"" + tooLong + "\"")).valid()).isFalse();
        }

        @Test
        void subtitle_is_optional() {
            assertThat(validate(VALID.replaceFirst("\"Coc Docs Platform · Legal Information\"", "\"\"")).valid()).isTrue();
        }

        @Test
        void subtitle_over_120_chars_fails() {
            String tooLong = "x".repeat(121);
            assertThat(validate(VALID.replaceFirst("\"Coc Docs Platform · Legal Information\"", "\"" + tooLong + "\"")).valid())
                    .isFalse();
        }

        @Test
        void invalid_status_fails() {
            assertThat(validate(VALID.replaceFirst("\"draft\"", "\"published\"")).valid()).isFalse();
        }

        @Test
        void missing_last_updated_fails() {
            assertThat(validate(VALID.replaceFirst("\"2026-10-01\"", "\"\"")).valid()).isFalse();
        }

        @Test
        void malformed_last_updated_fails() {
            assertThat(validate(VALID.replaceFirst("\"2026-10-01\"", "\"Oct 1 2026\"")).valid()).isFalse();
        }

        @Test
        void missing_updated_by_fails() {
            assertThat(validate(VALID.replaceFirst("\"the Platform Team\"", "\"\"")).valid()).isFalse();
        }

        @Test
        void updated_by_over_60_chars_fails() {
            String tooLong = "x".repeat(61);
            assertThat(validate(VALID.replaceFirst("\"the Platform Team\"", "\"" + tooLong + "\"")).valid()).isFalse();
        }

        @Test
        void empty_end_note_is_allowed() {
            assertThat(validate(VALID.replaceFirst("\\*Subject to change\\.\\*", "")).valid()).isTrue();
        }

        @Test
        void end_note_with_html_fails() {
            assertThat(validate(VALID.replaceFirst("\\*Subject to change\\.\\*", "<script>alert(1)</script>")).valid())
                    .isFalse();
        }
    }

    private static final String ENVELOPE_WITHOUT_SECTIONS = """
            {
              "title": "Terms of Service",
              "subtitle": "Coc Docs Platform · Legal Information",
              "status": "draft",
              "lastUpdated": "2026-10-01",
              "updatedBy": "the Platform Team",
              "endNote": "*Subject to change.*",
              "sections": %s
            }
            """;

    @Nested
    @DisplayName("sections")
    class Sections {
        @Test
        void not_an_array_fails() {
            assertThat(validate(ENVELOPE_WITHOUT_SECTIONS.formatted("\"nope\"")).valid()).isFalse();
        }

        @Test
        void empty_sections_fails() {
            assertThat(validate(ENVELOPE_WITHOUT_SECTIONS.formatted("[]")).valid()).isFalse();
        }

        @Test
        void more_than_30_sections_fails() {
            StringBuilder sections = new StringBuilder("[");
            for (int i = 0; i < 31; i++) {
                if (i > 0) sections.append(",");
                sections.append("{\"id\": \"s").append(i).append("\", \"title\": \"S").append(i).append("\", \"body\": \"x\"}");
            }
            sections.append("]");
            assertThat(validate(ENVELOPE_WITHOUT_SECTIONS.formatted(sections.toString())).valid()).isFalse();
        }

        @Test
        void missing_section_title_fails() {
            assertThat(validate(VALID.replaceFirst("\"title\": \"Intro\"", "\"title\": \"\"")).valid()).isFalse();
        }

        @Test
        void missing_section_body_fails() {
            assertThat(validate(VALID.replaceFirst("\"body\": \"Hello there\\.\"", "\"body\": \"\"")).valid()).isFalse();
        }

        @Test
        void section_body_over_4000_chars_fails() {
            String tooLong = "x".repeat(4001);
            assertThat(validate(VALID.replaceFirst("\"Hello there\\.\"", "\"" + tooLong + "\"")).valid()).isFalse();
        }

        @Test
        void duplicate_anchor_ids_fail() {
            assertThat(validate(VALID.replaceFirst("\"rules\"", "\"intro\"")).valid()).isFalse();
        }

        @Test
        void missing_anchor_id_fails() {
            assertThat(validate(VALID.replaceFirst("\"id\": \"intro\"", "\"id\": \"\"")).valid()).isFalse();
        }
    }
}
