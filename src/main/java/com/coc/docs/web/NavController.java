package com.coc.docs.web;

import com.coc.docs.nav.NavService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
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
}
