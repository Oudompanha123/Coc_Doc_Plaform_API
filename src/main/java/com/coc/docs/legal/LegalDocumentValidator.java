package com.coc.docs.legal;

import com.coc.docs.content.ValidationIssue;
import com.coc.docs.content.ValidationResult;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Authoritative validation of the Terms of Service document shape (task
 * "Admin 'Legal pages' editor ONLY") — mirrors the frontend's {@code
 * legal-document-validate.ts} {@code validateLegalDocument} exactly, so the
 * two never disagree (ADR-012 §3). Privacy keeps the original highlights/CTA
 * shape and {@link LegalContentValidator}; {@link LegalPageService} picks
 * one or the other by slug.
 */
@Component
public class LegalDocumentValidator {

    public static final int MAX_TITLE_LENGTH = 80;
    public static final int MAX_SUBTITLE_LENGTH = 120;
    public static final int MAX_SECTION_TITLE_LENGTH = 80;
    public static final int MAX_SECTION_BODY_LENGTH = 4_000;
    public static final int MAX_END_NOTE_LENGTH = 400;
    public static final int MAX_UPDATED_BY_LENGTH = 60;
    public static final int MAX_SECTIONS = 30;

    private static final Pattern DATE = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");
    private static final Set<String> VALID_STATUS = Set.of("draft", "approved");

    private final MarkdownValidator markdown;

    public LegalDocumentValidator(MarkdownValidator markdown) {
        this.markdown = markdown;
    }

    public ValidationResult validate(JsonNode content) {
        List<ValidationIssue> issues = new ArrayList<>();

        if (content == null || !content.isObject()) {
            return ValidationResult.failed(
                    List.of(new ValidationIssue("content", "content must be an object")));
        }

        requireNonBlank(content, "title", MAX_TITLE_LENGTH, issues);
        optionalMaxLength(content, "subtitle", MAX_SUBTITLE_LENGTH, issues);

        JsonNode status = content.get("status");
        if (!isText(status) || !VALID_STATUS.contains(status.asText())) {
            issues.add(new ValidationIssue("status", "status must be \"draft\" or \"approved\""));
        }

        JsonNode lastUpdated = content.get("lastUpdated");
        if (!isText(lastUpdated) || !DATE.matcher(lastUpdated.asText()).matches()) {
            issues.add(new ValidationIssue("lastUpdated", "lastUpdated is required"));
        }

        requireNonBlank(content, "updatedBy", MAX_UPDATED_BY_LENGTH, issues);

        JsonNode endNote = content.get("endNote");
        String endNoteText = isText(endNote) ? endNote.asText() : "";
        if (!endNoteText.isBlank()) {
            String violation = markdown.validate(endNoteText, MAX_END_NOTE_LENGTH, "End note");
            if (violation != null) {
                issues.add(new ValidationIssue("endNote", violation));
            }
        }

        validateSections(content.get("sections"), issues);

        return issues.isEmpty() ? ValidationResult.ok() : ValidationResult.failed(issues);
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

            JsonNode body = section.get("body");
            String violation = markdown.validate(isText(body) ? body.asText() : null, MAX_SECTION_BODY_LENGTH, "Body text");
            if (violation != null) {
                issues.add(new ValidationIssue(path + ".body", violation));
            }
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
