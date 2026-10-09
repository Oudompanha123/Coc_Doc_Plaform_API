package com.coc.docs.legal;

import com.coc.docs.content.ValidationIssue;
import com.coc.docs.content.ValidationResult;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Authoritative validation of a legal page's content, mirroring {@code
 * BlockBodyValidator}'s role for page bodies: the frontend's own check is
 * advisory (ADR-012 §3), this is what a write path actually enforces.
 *
 * <p>Limits match the admin editor's own client-side limits exactly, so the
 * two never disagree about what "too long" means.
 */
@Component
public class LegalContentValidator {

    public static final int MAX_TITLE_LENGTH = 80;
    public static final int MAX_LEAD_LENGTH = 160;
    public static final int MAX_EYEBROW_LENGTH = 40;
    public static final int MAX_HIGHLIGHT_TITLE_LENGTH = 40;
    public static final int MAX_HIGHLIGHT_TEXT_LENGTH = 120;
    public static final int HIGHLIGHT_COUNT = 3;
    public static final int MAX_SECTION_TITLE_LENGTH = 80;
    public static final int MAX_SECTIONS = 15;
    public static final int MAX_PLAIN_LANGUAGE_LENGTH = 600;
    public static final int MAX_SECTION_BODY_LENGTH = 2_000;
    public static final int MAX_CTA_TITLE_LENGTH = 40;
    public static final int MAX_CTA_TEXT_LENGTH = 120;

    private static final Set<String> VALID_PAGE_STATUS = Set.of("draft", "approved");
    private static final Set<String> VALID_BULLET_STYLE = Set.of("circle", "check");

    private final MarkdownValidator markdown;

    public LegalContentValidator(MarkdownValidator markdown) {
        this.markdown = markdown;
    }

    public ValidationResult validate(JsonNode content) {
        List<ValidationIssue> issues = new ArrayList<>();

        if (content == null || !content.isObject()) {
            return ValidationResult.failed(
                    List.of(new ValidationIssue("content", "content must be an object")));
        }

        requireNonBlank(content, "title", MAX_TITLE_LENGTH, issues);
        requireNonBlank(content, "lastUpdated", 32, issues);
        optionalMaxLength(content, "lead", MAX_LEAD_LENGTH, issues);
        optionalMaxLength(content, "eyebrow", MAX_EYEBROW_LENGTH, issues);

        JsonNode status = content.get("status");
        if (!isText(status) || !VALID_PAGE_STATUS.contains(status.asText())) {
            issues.add(new ValidationIssue("status", "status must be \"draft\" or \"approved\""));
        }

        JsonNode bulletStyle = content.get("bulletStyle");
        if (!isText(bulletStyle) || !VALID_BULLET_STYLE.contains(bulletStyle.asText())) {
            issues.add(new ValidationIssue("bulletStyle", "bulletStyle must be \"circle\" or \"check\""));
        }

        validateHighlights(content.get("highlights"), issues);
        validateMarkdownField(content.get("plainLanguage"), MAX_PLAIN_LANGUAGE_LENGTH, "plainLanguage", "Plain language", issues);
        validateCta(content.get("cta"), issues);
        validateSections(content.get("sections"), issues);

        return issues.isEmpty() ? ValidationResult.ok() : ValidationResult.failed(issues);
    }

    private void validateCta(JsonNode cta, List<ValidationIssue> issues) {
        if (cta == null || !cta.isObject()) {
            issues.add(new ValidationIssue("cta", "cta must be an object"));
            return;
        }
        requireNonBlank(cta, "title", MAX_CTA_TITLE_LENGTH, "cta", issues);
        requireNonBlank(cta, "text", MAX_CTA_TEXT_LENGTH, "cta", issues);
    }

    private void validateMarkdownField(
            JsonNode value, int maxLength, String field, String label, List<ValidationIssue> issues) {
        String text = isText(value) ? value.asText() : null;
        String violation = markdown.validate(text, maxLength, label);
        if (violation != null) {
            issues.add(new ValidationIssue(field, violation));
        }
    }

    private void validateHighlights(JsonNode highlights, List<ValidationIssue> issues) {
        if (!isArray(highlights)) {
            issues.add(new ValidationIssue("highlights", "highlights must be an array"));
            return;
        }
        if (highlights.size() != HIGHLIGHT_COUNT) {
            issues.add(new ValidationIssue(
                    "highlights", "there must be exactly " + HIGHLIGHT_COUNT + " highlight cards"));
        }
        for (int i = 0; i < highlights.size(); i++) {
            String path = "highlights[" + i + "]";
            JsonNode card = highlights.get(i);
            if (!card.isObject()) {
                issues.add(new ValidationIssue(path, "highlight must be an object"));
                continue;
            }
            requireNonBlank(card, "icon", 40, path, issues);
            requireNonBlank(card, "title", MAX_HIGHLIGHT_TITLE_LENGTH, path, issues);
            requireNonBlank(card, "text", MAX_HIGHLIGHT_TEXT_LENGTH, path, issues);
        }
    }

    private void validateSections(JsonNode sections, List<ValidationIssue> issues) {
        if (!isArray(sections)) {
            issues.add(new ValidationIssue("sections", "sections must be an array"));
            return;
        }
        if (sections.isEmpty()) {
            issues.add(new ValidationIssue("sections", "there must be at least one section"));
        }
        if (sections.size() > MAX_SECTIONS) {
            issues.add(new ValidationIssue(
                    "sections", "there are " + sections.size() + " sections; the maximum is " + MAX_SECTIONS));
        }

        Set<String> seenIds = new HashSet<>();
        for (int i = 0; i < sections.size(); i++) {
            String path = "sections[" + i + "]";
            JsonNode section = sections.get(i);
            if (!section.isObject()) {
                issues.add(new ValidationIssue(path, "section must be an object"));
                continue;
            }

            JsonNode id = section.get("id");
            if (!isText(id) || id.asText().isBlank()) {
                issues.add(new ValidationIssue(path + ".id", "every section needs an anchor id"));
            } else if (!seenIds.add(id.asText())) {
                issues.add(new ValidationIssue(
                        path + ".id", "anchor \"" + id.asText() + "\" is used by more than one section"));
            }

            requireNonBlank(section, "title", MAX_SECTION_TITLE_LENGTH, path, issues);
            requireNonBlank(section, "icon", 40, path, issues);
            validateMarkdownField(section.get("body"), MAX_SECTION_BODY_LENGTH, path + ".body", "Body text", issues);
        }
    }

    private void requireNonBlank(JsonNode parent, String field, int maxLength, List<ValidationIssue> issues) {
        requireNonBlank(parent, field, maxLength, null, issues);
    }

    private void requireNonBlank(
            JsonNode parent, String field, int maxLength, String parentPath, List<ValidationIssue> issues) {
        String path = parentPath == null ? field : parentPath + "." + field;
        JsonNode value = parent.get(field);
        if (!isText(value) || value.asText().isBlank()) {
            issues.add(new ValidationIssue(path, field + " is required"));
        } else if (value.asText().length() > maxLength) {
            issues.add(new ValidationIssue(path, field + " exceeds " + maxLength + " characters"));
        }
    }

    private void optionalMaxLength(JsonNode parent, String field, int maxLength, List<ValidationIssue> issues) {
        JsonNode value = parent.get(field);
        if (value == null || value.isNull()) {
            return;
        }
        if (!isText(value)) {
            issues.add(new ValidationIssue(field, field + " must be a string"));
        } else if (value.asText().length() > maxLength) {
            issues.add(new ValidationIssue(field, field + " exceeds " + maxLength + " characters"));
        }
    }

    private static boolean isText(JsonNode n) {
        return n != null && n.isTextual();
    }

    private static boolean isArray(JsonNode n) {
        return n != null && n.isArray();
    }
}
