package com.coc.docs.web;

import com.coc.docs.docscontent.DocsContentService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public read side of the docs-content store: the hero/steps/sections
 * content behind the product docs pages ({@code /docs/**}, {@code
 * /jex3-0/**}, ...).
 *
 * <p>Unlike {@link ContentController} (the block-based reader CMS, gated at
 * a {@code user} floor), this is {@code permitAll} in {@link
 * com.coc.docs.config.SecurityConfig} — the docs pages it backs have no auth
 * gate on the frontend today and are meant to be read by anyone, signed in
 * or not.
 *
 * <p>404 when nothing has been saved yet for a slug — not an empty body —
 * so the frontend can tell "no admin edit exists" from "an edit exists and
 * is empty" and fall back to its own seed content for the former.
 *
 * <p>// req: ADR-012 investigation, 2026-09-15
 */
@RestController
@RequestMapping("/api/v1/docs-content")
public class DocsContentController {

    private final DocsContentService content;

    public DocsContentController(DocsContentService content) {
        this.content = content;
    }

    @GetMapping("/{productKey}/{slug}")
    JsonNode get(@PathVariable String productKey, @PathVariable String slug) {
        return content.find(productKey, slug)
                .orElseThrow(() -> ApiException.notFound("docs content for " + productKey + "/" + slug));
    }

    /** {@code updatedAt} for the product docs pages' "About this document"
     *  panel — a separate call rather than a field on {@link #get}, so that
     *  endpoint's response stays exactly the `DocContent` shape the frontend
     *  reads it as (see {@link ApiDtos.DocsContentMetaResponse}). */
    @GetMapping("/{productKey}/{slug}/meta")
    ApiDtos.DocsContentMetaResponse meta(@PathVariable String productKey, @PathVariable String slug) {
        return content.findUpdatedAt(productKey, slug)
                .map(ApiDtos.DocsContentMetaResponse::new)
                .orElseThrow(() -> ApiException.notFound("docs content for " + productKey + "/" + slug));
    }
}
