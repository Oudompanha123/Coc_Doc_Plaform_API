package com.coc.docs.legal;

import com.coc.docs.content.ValidationIssue;
import com.coc.docs.content.ValidationResult;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Authoritative validation of a legal page — Terms of Service and Privacy
 * Policy share one shape since V81: title, subtitle, last-updated byline and
 * one Markdown {@code body}, whose {@code ##} headings are its sections
 * (product owner, 2026-10-14: "just one text editor"). Mirrors the
 * frontend's {@code legal-document-validate.ts} exactly, so the two never
 * disagree (ADR-012 §3).
 *
 * // req: ADR-012 §3
 */
@Component
public class LegalDocumentValidator {

    public static final int MAX_TITLE_LENGTH = 80;
    public static final int MAX_SUBTITLE_LENGTH = 160;
    public static final int MAX_UPDATED_BY_LENGTH = 60;
    /** The whole document — what thirty 4,000-character sections allowed before. */
    public static final int MAX_BODY_LENGTH = 120_000;

    private static final Pattern DATE = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");

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

        JsonNode lastUpdated = content.get("lastUpdated");
        if (!isText(lastUpdated) || !DATE.matcher(lastUpdated.asText()).matches()) {
            issues.add(new ValidationIssue("lastUpdated", "lastUpdated is required"));
        }

        requireNonBlank(content, "updatedBy", MAX_UPDATED_BY_LENGTH, issues);

        JsonNode body = content.get("body");
        String violation = markdown.validate(isText(body) ? body.asText() : null, MAX_BODY_LENGTH, "Content");
        if (violation != null) {
            issues.add(new ValidationIssue("body", violation));
        }

        return issues.isEmpty() ? ValidationResult.ok() : ValidationResult.failed(issues);
    }

    private void requireNonBlank(JsonNode parent, String field, int maxLength, List<ValidationIssue> issues) {
        JsonNode value = parent.get(field);
        if (!isText(value) || value.asText().isBlank()) {
            issues.add(new ValidationIssue(field, field + " is required"));
        } else if (value.asText().length() > maxLength) {
            issues.add(new ValidationIssue(field, field + " exceeds " + maxLength + " characters"));
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
}
