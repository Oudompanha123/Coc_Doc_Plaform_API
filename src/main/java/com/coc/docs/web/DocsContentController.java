package com.coc.docs.web;

import com.coc.docs.docscontent.DocsContentService;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Arrays;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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

    /**
     * Bulk existence check — which of the given slugs already have a saved
     * admin row, in one round trip. Exists so {@code ProductContentTable} can
     * render every row's "Has content"/"Not written" status without issuing
     * one 404-prone {@link #get} per row: each row that has never been saved
     * 404s there by design (see this class's own javadoc above), which is
     * harmless but noisy in the browser's network tab at N rows per page
     * load.
     *
     * <p>Always {@code 200}, even when none of the requested slugs have been
     * saved — that is the ordinary "nothing written yet" case, not an error.
     * {@code slugs} is a comma-separated list; a missing, blank, or
     * all-blank value is treated as "no candidates" and answered with an
     * empty result rather than a {@code 400}, matching the resilient-by-
     * default style the frontend's own read helpers already use for this
     * content (see {@code docs-content-api.ts}).
     *
     * <p>Never returns seed content as saved — this reads only {@code
     * docs_content} itself, which is the one thing {@link DocsContentService}
     * can see; the frontend's placeholder seed text lives entirely on its
     * side and never reaches this table.
     *
     * <p>// req: ADR-012 investigation, 2026-09-15
     */
    @GetMapping("/{productKey}")
    ApiDtos.DocsContentExistenceResponse existence(
            @PathVariable String productKey, @RequestParam(required = false) String slugs) {
        return new ApiDtos.DocsContentExistenceResponse(
                List.copyOf(content.findSavedSlugs(productKey, parseSlugs(slugs))));
    }

    private static List<String> parseSlugs(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .distinct()
                .toList();
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
