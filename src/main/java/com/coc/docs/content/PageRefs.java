package com.coc.docs.content;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Collects every internal page reference in a body.
 *
 * <p>Because a link stores {@code pageId} rather than a literal URL (ADR-003
 * section 6), answering "what links to this page?" is a lookup rather than a
 * text search across content. That is what makes the delete check in
 * FR-CONT-007 possible at all: before a page is soft-deleted, the pages that
 * reference it can be listed exactly instead of approximately.
 *
 * <p>A port of {@code collectPageRefs} in {@code frontend/lib/content/validate.ts},
 * with one difference: reference ids are parsed as UUIDs and non-UUID values
 * are skipped. The validator only guarantees {@code pageId} is a non-empty
 * string, and a caller here is about to put the value into a SQL parameter.
 *
 * <p>// req: FR-CONT-007, FR-READ-003
 */
public final class PageRefs {

    private PageRefs() {}

    /** Insertion-ordered so that callers and tests see a stable sequence. */
    public static Set<UUID> collect(JsonNode body) {
        Set<UUID> refs = new LinkedHashSet<>();
        if (body != null && body.isArray()) {
            walk(body, refs);
        }
        return refs;
    }

    private static void walk(JsonNode blocks, Set<UUID> refs) {
        for (JsonNode block : blocks) {
            if (!block.isObject()) {
                continue;
            }
            JsonNode typeNode = block.get("type");
            BlockType type = typeNode != null && typeNode.isTextual()
                    ? BlockType.fromWire(typeNode.asText())
                    : null;
            if (type == null) {
                continue;
            }

            switch (type) {
                case HEADING, PARAGRAPH -> fromRichText(block.get("text"), refs);

                case LIST -> forEach(block.get("items"), item -> fromRichText(item, refs));

                case TABLE -> {
                    forEach(block.get("headers"), header -> fromRichText(header, refs));
                    forEach(block.get("rows"), row -> forEach(row, cell -> fromRichText(cell, refs)));
                }

                case CALLOUT -> {
                    JsonNode nested = block.get("body");
                    if (nested != null && nested.isArray()) {
                        walk(nested, refs);
                    }
                }

                case STEPS -> forEach(block.get("items"), step -> {
                    fromRichText(step.get("title"), refs);
                    JsonNode nested = step.get("body");
                    if (nested != null && nested.isArray()) {
                        walk(nested, refs);
                    }
                });

                case CARDS -> forEach(block.get("items"), card -> {
                    fromRichText(card.get("title"), refs);
                    fromRichText(card.get("body"), refs);
                    addLink(card.get("link"), refs);
                });

                // code, image and embed carry no links.
                case CODE, IMAGE, EMBED -> { }
            }
        }
    }

    private static void fromRichText(JsonNode richText, Set<UUID> refs) {
        forEach(richText, node -> addLink(node.get("link"), refs));
    }

    private static void addLink(JsonNode link, Set<UUID> refs) {
        if (link == null || !link.isObject()) {
            return;
        }
        JsonNode pageId = link.get("pageId");
        if (pageId == null || !pageId.isTextual()) {
            return;
        }
        try {
            refs.add(UUID.fromString(pageId.asText()));
        } catch (IllegalArgumentException e) {
            // Not a UUID, so it cannot match a page row. Silently skipped rather
            // than thrown: this is a read-side scan over already-stored content,
            // and a malformed reference is a reporting gap, not a failure that
            // should block a delete check from returning its other results.
        }
    }

    private static void forEach(JsonNode array, java.util.function.Consumer<JsonNode> fn) {
        if (array == null || !array.isArray()) {
            return;
        }
        for (JsonNode element : array) {
            if (element != null && !element.isNull()) {
                fn.accept(element);
            }
        }
    }
}
