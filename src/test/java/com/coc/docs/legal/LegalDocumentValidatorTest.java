package com.coc.docs.legal;

import static org.assertj.core.api.Assertions.assertThat;

import com.coc.docs.content.ValidationIssue;
import com.coc.docs.content.ValidationResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for the authoritative legal-page validator (V81 shape: title,
 * subtitle, byline, one Markdown body) — mirrors {@code
 * legal-document-validate.ts}. The Markdown whitelist itself is covered in
 * {@link MarkdownValidatorTest}; this class checks the envelope.
 */
class LegalDocumentValidatorTest {

    private final ObjectMapper json = new ObjectMapper();
    private final LegalDocumentValidator validator = new LegalDocumentValidator(new MarkdownValidator());

    private ObjectNode valid() {
        return json.createObjectNode()
                .put("title", "Terms of Service")
                .put("subtitle", "Coc Docs Platform · Legal Information")
                .put("lastUpdated", "2026-10-01")
                .put("updatedBy", "the Platform Team")
                .put("body", "## Agreement\n\nBy using the Platform you agree.\n\n## Contact\n\n[Email us](mailto:legal@example.com)");
    }

    private ValidationResult validate(ObjectNode content) {
        return validator.validate(content);
    }

    private static boolean hasIssueAt(ValidationResult result, String path) {
        return result.issues().stream().map(ValidationIssue::path).anyMatch(path::equals);
    }

    @Test
    @DisplayName("a complete page passes")
    void valid_page_passes() {
        assertThat(validate(valid()).valid()).isTrue();
    }

    @Test
    @DisplayName("a non-object is refused outright")
    void non_object_is_refused() {
        assertThat(validator.validate(json.createArrayNode()).valid()).isFalse();
        assertThat(validator.validate(null).valid()).isFalse();
    }

    @Test
    @DisplayName("title, last updated, updated by and body are required")
    void required_fields() {
        ValidationResult result = validate(json.createObjectNode());
        assertThat(hasIssueAt(result, "title")).isTrue();
        assertThat(hasIssueAt(result, "lastUpdated")).isTrue();
        assertThat(hasIssueAt(result, "updatedBy")).isTrue();
        assertThat(hasIssueAt(result, "body")).isTrue();
        assertThat(hasIssueAt(result, "subtitle")).as("subtitle is optional").isFalse();
    }

    @Test
    @DisplayName("a blank body is refused")
    void blank_body_is_refused() {
        assertThat(hasIssueAt(validate(valid().put("body", "  \n ")), "body")).isTrue();
    }

    @Test
    @DisplayName("lastUpdated must be a yyyy-mm-dd date")
    void last_updated_format() {
        assertThat(hasIssueAt(validate(valid().put("lastUpdated", "1 Oct 2026")), "lastUpdated")).isTrue();
    }

    @Test
    @DisplayName("length limits apply to title, subtitle, updatedBy and body")
    void length_limits() {
        assertThat(hasIssueAt(validate(valid().put("title", "x".repeat(LegalDocumentValidator.MAX_TITLE_LENGTH + 1))), "title")).isTrue();
        assertThat(hasIssueAt(validate(valid().put("subtitle", "x".repeat(LegalDocumentValidator.MAX_SUBTITLE_LENGTH + 1))), "subtitle")).isTrue();
        assertThat(hasIssueAt(validate(valid().put("updatedBy", "x".repeat(LegalDocumentValidator.MAX_UPDATED_BY_LENGTH + 1))), "updatedBy")).isTrue();
        assertThat(hasIssueAt(validate(valid().put("body", "x".repeat(LegalDocumentValidator.MAX_BODY_LENGTH + 1))), "body")).isTrue();
    }

    @Test
    @DisplayName("the body goes through the Markdown whitelist — raw HTML and unsafe links are refused")
    void body_uses_markdown_rules() {
        assertThat(hasIssueAt(validate(valid().put("body", "## Hi\n\n<script>alert(1)</script>")), "body")).isTrue();
        assertThat(hasIssueAt(validate(valid().put("body", "[x](javascript:alert(1))")), "body")).isTrue();
    }

    @Test
    @DisplayName("old shape fields are ignored, not required")
    void old_fields_are_not_required() {
        ObjectNode content = valid();
        content.remove("subtitle");
        assertThat(validate(content).valid()).isTrue();
    }
}
