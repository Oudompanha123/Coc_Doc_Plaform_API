package com.coc.docs.content;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Authoritative validation of page bodies.
 *
 * <p>A Java port of {@code frontend/lib/content/validate.ts}, and the only one
 * of the two that matters: ADR-012 section 3 makes this side authoritative and
 * the TypeScript side advisory. A frontend that skips its own check cannot
 * inject anything, because no write path reaches the database without passing
 * through here.
 *
 * <p>This is a security control as much as a correctness one. Combined with the
 * absence of a raw-html block type (ADR-010 section 3), there is no field in
 * which markup is legal, so there is no path by which an editor can persist
 * markup that a renderer would execute.
 *
 * <p><b>Why {@link JsonNode} rather than a typed block hierarchy.</b> The body
 * is stored as {@code jsonb} and round-tripped verbatim; nothing on the backend
 * renders blocks, so a sealed record hierarchy would buy typed access that no
 * caller wants, and would report failures as Jackson binding errors naming Java
 * classes instead of the offending block. Validating the tree directly is also
 * the closer analogue of the TypeScript validator, which operates on
 * {@code unknown} for the same reason.
 *
 * <p>// req: FR-CONT-009, NFR-SEC-SANITIZE, ADR-010, ADR-011, ADR-012
 */
@Component
public class BlockBodyValidator {

    /** Bounds a hostile or accidental deep structure. Mirrors MAX_NESTING_DEPTH. */
    public static final int MAX_NESTING_DEPTH = 3;

    /** Bounds payload size independently of the HTTP body limit. */
    public static final int MAX_BLOCKS_PER_PAGE = 500;

    public static final int MAX_TEXT_LENGTH = 20_000;

    private static final List<String> INLINE_MARKS = List.of("bold", "italic", "code");

    /** Components an {@code embed} block may reference. Closed set, resolved by the frontend. */
    public static final List<String> EMBEDDABLE_COMPONENTS = List.of("HarnessDemo", "OwnerTree");

    private static final Set<String> SAFE_SCHEMES = Set.of("http", "https");

    private static final java.util.Locale ROOT = java.util.Locale.ROOT;

    // -----------------------------------------------------------------------
    // Entry point
    // -----------------------------------------------------------------------

    /**
     * Validates a page body. Call this on every write path before persisting.
     *
     * @param body the parsed body, expected to be a JSON array of blocks
     */
    public ValidationResult validate(JsonNode body) {
        List<ValidationIssue> issues = new ArrayList<>();

        if (body == null || !body.isArray()) {
            return ValidationResult.failed(
                    List.of(new ValidationIssue("body", "body must be an array of blocks")));
        }
        if (body.size() > MAX_BLOCKS_PER_PAGE) {
            issues.add(new ValidationIssue(
                    "body",
                    "page has " + body.size() + " blocks; the maximum is " + MAX_BLOCKS_PER_PAGE));
        }

        for (int i = 0; i < body.size(); i++) {
            validateBlock(body.get(i), "body[" + i + "]", 0, issues);
        }

        return issues.isEmpty() ? ValidationResult.ok() : ValidationResult.failed(issues);
    }

    // -----------------------------------------------------------------------
    // Blocks
    // -----------------------------------------------------------------------

    private void validateBlock(JsonNode block, String path, int depth, List<ValidationIssue> issues) {
        if (!isObject(block)) {
            issues.add(new ValidationIssue(path, "block must be an object"));
            return;
        }

        JsonNode typeNode = block.get("type");
        BlockType type = isText(typeNode) ? BlockType.fromWire(typeNode.asText()) : null;

        if (type == null) {
            String named = isText(typeNode) ? " \"" + typeNode.asText() + "\"" : "";
            issues.add(new ValidationIssue(
                    path + ".type",
                    "unknown block type" + named + "; the vocabulary is " + BlockType.vocabulary()));
            return;
        }

        switch (type) {
            case HEADING -> validateHeading(block, path, issues);
            case PARAGRAPH -> validateRichText(block.get("text"), path + ".text", issues);
            case LIST -> validateList(block, path, issues);
            case TABLE -> validateTable(block, path, issues);
            case CODE -> validateCode(block, path, issues);
            case CALLOUT -> validateCallout(block, path, depth, issues);
            case STEPS -> validateSteps(block, path, depth, issues);
            case CARDS -> validateCards(block, path, issues);
            case IMAGE -> validateImage(block, path, issues);
            case EMBED -> validateEmbed(block, path, issues);
        }
    }

    private void validateHeading(JsonNode block, String path, List<ValidationIssue> issues) {
        JsonNode level = block.get("level");
        int value = level != null && level.isInt() ? level.asInt() : -1;
        if (value != 2 && value != 3) {
            issues.add(new ValidationIssue(path + ".level", "heading level must be 2 or 3"));
        }
        validateRichText(block.get("text"), path + ".text", issues);
    }

    private void validateList(JsonNode block, String path, List<ValidationIssue> issues) {
        if (!isBoolean(block.get("ordered"))) {
            issues.add(new ValidationIssue(path + ".ordered", "ordered must be a boolean"));
        }
        JsonNode items = block.get("items");
        if (!isArray(items)) {
            issues.add(new ValidationIssue(path + ".items", "items must be an array"));
            return;
        }
        for (int i = 0; i < items.size(); i++) {
            validateRichText(items.get(i), path + ".items[" + i + "]", issues);
        }
    }

    private void validateTable(JsonNode block, String path, List<ValidationIssue> issues) {
        JsonNode headers = block.get("headers");
        if (!isArray(headers)) {
            issues.add(new ValidationIssue(path + ".headers", "headers must be an array"));
        } else {
            for (int i = 0; i < headers.size(); i++) {
                validateRichText(headers.get(i), path + ".headers[" + i + "]", issues);
            }
        }

        JsonNode rows = block.get("rows");
        if (!isArray(rows)) {
            issues.add(new ValidationIssue(path + ".rows", "rows must be an array"));
            return;
        }

        int width = isArray(headers) ? headers.size() : -1;
        for (int r = 0; r < rows.size(); r++) {
            JsonNode row = rows.get(r);
            if (!isArray(row)) {
                issues.add(new ValidationIssue(
                        path + ".rows[" + r + "]", "row must be an array of cells"));
                continue;
            }
            // Ragged tables render unpredictably; reject at the boundary instead.
            if (width >= 0 && row.size() != width) {
                issues.add(new ValidationIssue(
                        path + ".rows[" + r + "]",
                        "row has " + row.size() + " cells but the table has " + width + " columns"));
            }
            for (int c = 0; c < row.size(); c++) {
                validateRichText(row.get(c), path + ".rows[" + r + "][" + c + "]", issues);
            }
        }
    }

    private void validateCode(JsonNode block, String path, List<ValidationIssue> issues) {
        if (!isText(block.get("language"))) {
            issues.add(new ValidationIssue(path + ".language", "language must be a string"));
        }
        JsonNode source = block.get("source");
        if (!isText(source)) {
            issues.add(new ValidationIssue(path + ".source", "source must be a string"));
        } else if (source.asText().length() > MAX_TEXT_LENGTH) {
            issues.add(new ValidationIssue(
                    path + ".source", "source exceeds " + MAX_TEXT_LENGTH + " characters"));
        }
        if (!isBoolean(block.get("copyable"))) {
            issues.add(new ValidationIssue(path + ".copyable", "copyable must be a boolean"));
        }
        JsonNode command = block.get("command");
        if (isPresent(command) && !isText(command)) {
            issues.add(new ValidationIssue(
                    path + ".command", "command must be a string when present"));
        }
    }

    private void validateCallout(JsonNode block, String path, int depth, List<ValidationIssue> issues) {
        JsonNode variant = block.get("variant");
        String v = isText(variant) ? variant.asText() : null;
        if (!"note".equals(v) && !"important".equals(v)) {
            issues.add(new ValidationIssue(path + ".variant", "variant must be note or important"));
        }
        validateNested(block.get("body"), path + ".body", depth, issues);
    }

    private void validateSteps(JsonNode block, String path, int depth, List<ValidationIssue> issues) {
        JsonNode items = block.get("items");
        if (!isArray(items)) {
            issues.add(new ValidationIssue(path + ".items", "items must be an array"));
            return;
        }
        for (int i = 0; i < items.size(); i++) {
            String p = path + ".items[" + i + "]";
            JsonNode item = items.get(i);
            if (!isObject(item)) {
                issues.add(new ValidationIssue(p, "step must be an object"));
                continue;
            }
            validateRichText(item.get("title"), p + ".title", issues);
            validateNested(item.get("body"), p + ".body", depth, issues);
        }
    }

    private void validateCards(JsonNode block, String path, List<ValidationIssue> issues) {
        JsonNode items = block.get("items");
        if (!isArray(items)) {
            issues.add(new ValidationIssue(path + ".items", "items must be an array"));
            return;
        }
        for (int i = 0; i < items.size(); i++) {
            String p = path + ".items[" + i + "]";
            JsonNode item = items.get(i);
            if (!isObject(item)) {
                issues.add(new ValidationIssue(p, "card must be an object"));
                continue;
            }
            JsonNode icon = item.get("icon");
            if (isPresent(icon) && !isText(icon)) {
                issues.add(new ValidationIssue(p + ".icon", "icon must be a string when present"));
            }
            validateRichText(item.get("title"), p + ".title", issues);
            validateRichText(item.get("body"), p + ".body", issues);
            if (isPresent(item.get("link"))) {
                validateLink(item.get("link"), p + ".link", issues);
            }
        }
    }

    private void validateImage(JsonNode block, String path, List<ValidationIssue> issues) {
        JsonNode assetId = block.get("assetId");
        if (!isText(assetId) || assetId.asText().isEmpty()) {
            issues.add(new ValidationIssue(path + ".assetId", "assetId is required"));
        }
        // Empty alt is legal (decorative images); a missing alt field is not.
        if (!isText(block.get("alt"))) {
            issues.add(new ValidationIssue(
                    path + ".alt", "alt text is required (use \"\" for decorative images)"));
        }
        if (isPresent(block.get("caption"))) {
            validateRichText(block.get("caption"), path + ".caption", issues);
        }
    }

    private void validateEmbed(JsonNode block, String path, List<ValidationIssue> issues) {
        JsonNode component = block.get("component");
        if (!isText(component) || !EMBEDDABLE_COMPONENTS.contains(component.asText())) {
            issues.add(new ValidationIssue(
                    path + ".component",
                    "unknown component; the registry contains "
                            + String.join(", ", EMBEDDABLE_COMPONENTS)));
        }
        JsonNode props = block.get("props");
        if (isPresent(props) && !isObject(props)) {
            issues.add(new ValidationIssue(path + ".props", "props must be an object"));
        }
    }

    private void validateNested(JsonNode value, String path, int depth, List<ValidationIssue> issues) {
        if (depth >= MAX_NESTING_DEPTH) {
            issues.add(new ValidationIssue(
                    path, "nesting exceeds the maximum depth of " + MAX_NESTING_DEPTH));
            return;
        }
        if (!isArray(value)) {
            issues.add(new ValidationIssue(path, "expected an array of blocks"));
            return;
        }
        for (int i = 0; i < value.size(); i++) {
            validateBlock(value.get(i), path + "[" + i + "]", depth + 1, issues);
        }
    }

    // -----------------------------------------------------------------------
    // Inline content
    // -----------------------------------------------------------------------

    private void validateRichText(JsonNode value, String path, List<ValidationIssue> issues) {
        if (!isArray(value)) {
            issues.add(new ValidationIssue(path, "expected rich text (an array of inline nodes)"));
            return;
        }
        for (int i = 0; i < value.size(); i++) {
            String p = path + "[" + i + "]";
            JsonNode node = value.get(i);
            if (!isObject(node)) {
                issues.add(new ValidationIssue(p, "inline node must be an object"));
                continue;
            }

            JsonNode text = node.get("text");
            if (!isText(text)) {
                issues.add(new ValidationIssue(p + ".text", "inline node requires a text string"));
            } else if (text.asText().length() > MAX_TEXT_LENGTH) {
                issues.add(new ValidationIssue(
                        p + ".text", "text exceeds " + MAX_TEXT_LENGTH + " characters"));
            }

            JsonNode marks = node.get("marks");
            if (isPresent(marks)) {
                if (!isArray(marks)) {
                    issues.add(new ValidationIssue(p + ".marks", "marks must be an array"));
                } else {
                    for (int j = 0; j < marks.size(); j++) {
                        JsonNode mark = marks.get(j);
                        if (!isText(mark) || !INLINE_MARKS.contains(mark.asText())) {
                            issues.add(new ValidationIssue(
                                    p + ".marks[" + j + "]",
                                    "unknown mark; expected one of " + String.join(", ", INLINE_MARKS)));
                        }
                    }
                }
            }

            if (isPresent(node.get("link"))) {
                validateLink(node.get("link"), p + ".link", issues);
            }
        }
    }

    private void validateLink(JsonNode link, String path, List<ValidationIssue> issues) {
        if (!isObject(link)) {
            issues.add(new ValidationIssue(path, "link must be an object"));
            return;
        }
        JsonNode pageId = link.get("pageId");
        JsonNode href = link.get("href");

        boolean hasPageId = isText(pageId) && !pageId.asText().isEmpty();
        boolean hasHref = isText(href);

        // Exactly one, so a link is either internal (a reference that survives a
        // rename) or external, never an ambiguous mixture.  // req: FR-READ-003
        if (hasPageId == hasHref) {
            issues.add(new ValidationIssue(path, "link must have exactly one of pageId or href"));
            return;
        }
        if (hasHref && !isSafeHref(href.asText())) {
            issues.add(new ValidationIssue(
                    path + ".href", "link must be a relative path or an http(s) URL"));
        }
    }

    /**
     * Only http(s) and same-site relative links are permitted. Blocks
     * {@code javascript:}, {@code data:}, {@code vbscript:} and similar schemes
     * at the point of persistence, rather than relying on the renderer to escape
     * them.
     *
     * <p>// req: NFR-SEC-SANITIZE, TM-010
     */
    static boolean isSafeHref(String href) {
        // A protocol-relative "//host" is rejected: it inherits the page scheme
        // and is an off-site link wearing a relative link's clothes.
        if (href.startsWith("/") && !href.startsWith("//")) {
            return true;
        }
        try {
            String scheme = new URI(href).getScheme();
            return scheme != null && SAFE_SCHEMES.contains(scheme.toLowerCase(ROOT));
        } catch (URISyntaxException e) {
            return false;
        }
    }

    // -----------------------------------------------------------------------
    // JsonNode predicates
    //
    // A JSON null and an absent field are treated alike, matching `undefined` on
    // the TypeScript side. Without this, {"marks": null} would pass a bare null
    // check and then fail the array check with a message an editor cannot act on.
    // -----------------------------------------------------------------------

    private static boolean isPresent(JsonNode n) {
        return n != null && !n.isNull();
    }

    private static boolean isObject(JsonNode n) {
        return n != null && n.isObject();
    }

    private static boolean isArray(JsonNode n) {
        return n != null && n.isArray();
    }

    private static boolean isText(JsonNode n) {
        return n != null && n.isTextual();
    }

    private static boolean isBoolean(JsonNode n) {
        return n != null && n.isBoolean();
    }
}
