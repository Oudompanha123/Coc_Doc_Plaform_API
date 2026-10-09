package com.coc.docs.web;

import com.coc.docs.nav.NavService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public read side of the nav structure — the product list and each
 * product's sub-items, replacing the frontend's hardcoded
 * {@code jexmcp-nav.ts}. {@code permitAll} in {@link
 * com.coc.docs.config.SecurityConfig}, same reasoning as {@link
 * DocsContentController}: the sidebar and docs pages it drives carry no
 * auth gate on the frontend.
 *
 * <p>// req: nav-structure investigation, 2026-09-15
 */
@RestController
@RequestMapping("/api/v1/nav")
public class NavController {

    private final NavService nav;

    public NavController(NavService nav) {
        this.nav = nav;
    }

    @GetMapping
    List<ApiDtos.NavProductResponse> get() {
        return nav.findAll().stream().map(ApiDtos.NavProductResponse::of).toList();
    }

    /** The redirect lookup a client issues when a product URL names a slug no
     *  live product currently has — e.g. the frontend's {@code middleware.ts}
     *  deciding whether an old {@code /docs/jex3-0} should 308 to {@code
     *  /docs/jex}. 404 when {@code slug} was never a product's key at all. */
    @GetMapping("/products/by-old-slug/{slug}")
    ApiDtos.CurrentSlugResponse currentSlugForOldSlug(@PathVariable String slug) {
        String currentKey =
                nav.findCurrentKeyForOldSlug(slug).orElseThrow(() -> ApiException.notFound("product slug " + slug));
        return new ApiDtos.CurrentSlugResponse(currentKey);
    }
}
