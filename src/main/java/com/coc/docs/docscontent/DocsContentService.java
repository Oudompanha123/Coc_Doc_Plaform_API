package com.coc.docs.docscontent;

import com.coc.docs.audit.AuditAction;
import com.coc.docs.audit.AuditRepository;
import com.coc.docs.auth.CurrentUser;
import com.coc.docs.web.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
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
    private final AuditRepository audit;
    private final ObjectMapper json;

    public DocsContentService(DocsContentRepository repository, AuditRepository audit, ObjectMapper json) {
        this.repository = repository;
        this.audit = audit;
        this.json = json;
    }

    public Optional<JsonNode> find(String productKey, String slug) {
        return repository.find(productKey, slug);
    }

    public Optional<Instant> findUpdatedAt(String productKey, String slug) {
        return repository.findUpdatedAt(productKey, slug);
    }

    public JsonNode save(String productKey, String slug, JsonNode body, CurrentUser editor) {
        String kind = body.path("kind").asText(null);
        if (kind == null || !KNOWN_KINDS.contains(kind)) {
            throw ApiException.badRequest(
                    "body.kind must be one of " + KNOWN_KINDS + ", got \"" + kind + "\"");
        }
        JsonNode saved = repository.upsert(productKey, slug, kind, body, editor.id());
        audit.append(
                editor.id(),
                editor.email(),
                AuditAction.DOCS_CONTENT_SAVE,
                AuditAction.TARGET_DOCS_CONTENT,
                null,
                json.createObjectNode().put("productKey", productKey).put("slug", slug).put("kind", kind));
        return saved;
    }
}
