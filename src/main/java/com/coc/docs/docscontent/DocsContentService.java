package com.coc.docs.docscontent;

import com.coc.docs.auth.CurrentUser;
import com.coc.docs.web.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * Business logic for {@code docs_content} — thin on purpose. This content has
 * no authorization-sensitive fields beyond the endpoint gate itself (writes
 * already sit behind the {@code admin} floor in {@code SecurityConfig}), and
 * its shape is a small, closed set of plain-text fields with no markup field
 * to sanitize (see {@code content-types.ts}), so the one rule worth
 * enforcing server-side is that {@code kind} is one this app actually knows
 * how to render.
 *
 * <p>// req: ADR-012 investigation, 2026-09-15
 */
@Service
public class DocsContentService {

    private static final Set<String> KNOWN_KINDS = Set.of("hero", "steps", "sections");

    private final DocsContentRepository repository;

    public DocsContentService(DocsContentRepository repository) {
        this.repository = repository;
    }

    public Optional<JsonNode> find(String productKey, String slug) {
        return repository.find(productKey, slug);
    }

    public JsonNode save(String productKey, String slug, JsonNode body, CurrentUser editor) {
        String kind = body.path("kind").asText(null);
        if (kind == null || !KNOWN_KINDS.contains(kind)) {
            throw ApiException.badRequest(
                    "body.kind must be one of " + KNOWN_KINDS + ", got \"" + kind + "\"");
        }
        return repository.upsert(productKey, slug, kind, body, editor.id());
    }
}
