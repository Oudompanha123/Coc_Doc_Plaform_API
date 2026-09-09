package com.coc.docs.content;

import java.util.List;

/**
 * The outcome of validating a page body.
 *
 * <p>A body is only obtainable from {@link #ok()}, so a caller cannot
 * accidentally persist an unvalidated value — the same property
 * {@code validatePageBody}'s narrowed return type gives the TypeScript side.
 *
 * <p>// req: FR-CONT-009, NFR-SEC-SANITIZE
 */
public record ValidationResult(boolean valid, List<ValidationIssue> issues) {

    public ValidationResult {
        issues = List.copyOf(issues);
    }

    public static ValidationResult ok() {
        return new ValidationResult(true, List.of());
    }

    public static ValidationResult failed(List<ValidationIssue> issues) {
        if (issues.isEmpty()) {
            throw new IllegalArgumentException("a failed validation must carry at least one issue");
        }
        return new ValidationResult(false, issues);
    }
}
