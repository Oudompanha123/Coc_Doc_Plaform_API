package com.coc.docs.web;

import com.coc.docs.auth.CurrentUser;
import com.coc.docs.nav.NavItem;
import com.coc.docs.nav.NavProduct;
import com.coc.docs.nav.NavService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Editorial API for the nav structure. Sits under {@code /api/v1/admin/**},
 * already covered by {@code SecurityConfig}'s {@code admin}-role rule for
 * that prefix — no new security line needed.
 *
 * <p>An empty sub-item slug (a product's own index route) can't travel as a
 * literal empty URL segment, so a path variable uses the literal string
 * {@code "index"} in its place — {@link #segmentToSlug} is the one place
 * that's translated back to {@code ""} before reaching the service, so
 * {@code nav_item.slug} always stores the real value.
 *
 * <p>// req: nav-structure investigation, 2026-09-15
 */
@RestController
@RequestMapping("/api/v1/admin/nav")
public class AdminNavController {

    private final NavService nav;

    public AdminNavController(NavService nav) {
        this.nav = nav;
    }

    @PostMapping("/products")
    ApiDtos.NavProductResponse createProduct(
            @Valid @RequestBody ApiDtos.CreateNavProductRequest request, @AuthenticationPrincipal CurrentUser editor) {
        NavProduct product = nav.createProduct(request.key(), request.label(), request.icon(), editor);
        return ApiDtos.NavProductResponse.of(product);
    }

    @PutMapping("/products/{key}")
    ResponseEntity<Void> updateProduct(
            @PathVariable String key, @Valid @RequestBody ApiDtos.UpdateNavProductRequest request) {
        nav.updateProduct(key, request.label(), request.icon());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/products/{key}")
    ResponseEntity<Void> deleteProduct(@PathVariable String key, @AuthenticationPrincipal CurrentUser editor) {
        nav.deleteProduct(key, editor);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/products/reorder")
    ResponseEntity<Void> reorderProducts(@Valid @RequestBody ApiDtos.ReorderProductsRequest request) {
        nav.reorderProducts(request.keys());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/products/{key}/items")
    ApiDtos.NavItemResponse createItem(
            @PathVariable String key,
            @Valid @RequestBody ApiDtos.CreateNavItemRequest request,
            @AuthenticationPrincipal CurrentUser editor) {
        NavItem item = nav.createItem(key, request.slug(), request.label(), editor);
        return ApiDtos.NavItemResponse.of(item);
    }

    @PutMapping("/products/{key}/items/{slugSegment}")
    ResponseEntity<Void> updateItem(
            @PathVariable String key,
            @PathVariable String slugSegment,
            @Valid @RequestBody ApiDtos.UpdateNavItemRequest request) {
        nav.updateItem(key, segmentToSlug(slugSegment), request.label());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/products/{key}/items/{slugSegment}")
    ResponseEntity<Void> deleteItem(
            @PathVariable String key, @PathVariable String slugSegment, @AuthenticationPrincipal CurrentUser editor) {
        nav.deleteItem(key, segmentToSlug(slugSegment), editor);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/products/{key}/items/reorder")
    ResponseEntity<Void> reorderItems(
            @PathVariable String key, @Valid @RequestBody ApiDtos.ReorderItemsRequest request) {
        nav.reorderItems(key, request.slugs());
        return ResponseEntity.noContent().build();
    }

    private static String segmentToSlug(String segment) {
        return "index".equals(segment) ? "" : segment;
    }
}
