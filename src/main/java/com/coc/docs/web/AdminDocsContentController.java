package com.coc.docs.web;

import com.coc.docs.auth.CurrentUser;
import com.coc.docs.docscontent.DocsContentService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Editorial write side of the docs-content store. Sits under {@code
 * /api/v1/admin/**}, so it is already covered by {@code SecurityConfig}'s
 * existing {@code admin}-role rule for that prefix — no new security line
 * needed for this endpoint specifically.
 *
 * <p>Last-write-wins (see {@link com.coc.docs.docscontent.DocsContentRepository}):
 * this content has no draft/publish workflow, so there is no concurrent-edit
 * lock to enforce here the way {@link AdminContentController} enforces one
 * via {@code versionNo}.
 *
 * <p>// req: ADR-012 investigation, 2026-09-15
 */
@RestController
@RequestMapping("/api/v1/admin/docs-content")
public class AdminDocsContentController {

    private final DocsContentService content;

    public AdminDocsContentController(DocsContentService content) {
        this.content = content;
    }

    @PutMapping("/{productKey}/{slug}")
    JsonNode save(
            @PathVariable String productKey,
            @PathVariable String slug,
            @RequestBody JsonNode body,
            @AuthenticationPrincipal CurrentUser editor) {

        return content.save(productKey, slug, body, editor);
    }
}
