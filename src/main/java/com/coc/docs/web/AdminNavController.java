package com.coc.docs.web;

import com.coc.docs.auth.CurrentUser;
import com.coc.docs.nav.NavGroup;
import com.coc.docs.nav.NavItem;
import com.coc.docs.nav.NavItemOverride;
import com.coc.docs.nav.NavProduct;
import com.coc.docs.nav.NavService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
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

    /** Everything the sidebar's kebab menu needs in one call: live products,
     *  every group, and every ADMINISTRATION-block item override. */
    @GetMapping
    ApiDtos.AdminNavConfigResponse config() {
        return new ApiDtos.AdminNavConfigResponse(
                nav.findAll().stream().map(ApiDtos.NavProductResponse::of).toList(),
                nav.findAllGroups().stream().map(ApiDtos.NavGroupResponse::of).toList(),
                nav.findAllItemOverrides().stream().map(ApiDtos.NavItemOverrideResponse::of).toList());
    }

    /** Rename and/or move one ADMINISTRATION-block item (super_admin +
     *  admin; {@code SecurityConfig}'s general {@code /admin/**} floor). */
    @PatchMapping("/items/{itemKey}")
    ApiDtos.NavItemOverrideResponse updateItemOverride(
            @PathVariable String itemKey,
            @RequestBody ApiDtos.UpdateNavItemOverrideRequest request,
            @AuthenticationPrincipal CurrentUser editor) {
        NavItemOverride override = nav.upsertItemOverride(decodeItemKey(itemKey), request.label(), request.groupId(), editor);
        return ApiDtos.NavItemOverrideResponse.of(override);
    }

    @PostMapping("/groups")
    ApiDtos.NavGroupResponse createGroup(
            @Valid @RequestBody ApiDtos.CreateNavGroupRequest request, @AuthenticationPrincipal CurrentUser editor) {
        NavGroup group = nav.createGroup(request.name(), editor);
        return ApiDtos.NavGroupResponse.of(group);
    }

    @PutMapping("/groups/{id}")
    ResponseEntity<Void> renameGroup(
            @PathVariable UUID id,
            @Valid @RequestBody ApiDtos.UpdateNavGroupRequest request,
            @AuthenticationPrincipal CurrentUser editor) {
        nav.renameGroup(id, request.name(), editor);
        return ResponseEntity.noContent().build();
    }

    /** super_admin only — narrowed in {@code SecurityConfig}, the same
     *  pattern as the product delete route below it. */
    @DeleteMapping("/groups/{id}")
    ResponseEntity<Void> deleteGroup(@PathVariable UUID id, @AuthenticationPrincipal CurrentUser editor) {
        nav.deleteGroup(id, editor);
        return ResponseEntity.noContent().build();
    }

    /** A static item's key is its href (e.g. {@code /legal-pages}), which a
     *  leading {@code /} would turn into an extra path segment if sent
     *  literally — the client sends it without one ({@code .../items/
     *  legal-pages}), restored here before it reaches the service, the same
     *  {@code "index"}-segment trick {@link #segmentToSlug} plays below. */
    private static String decodeItemKey(String pathVariable) {
        return pathVariable.startsWith("/") ? pathVariable : "/" + pathVariable;
    }

    @PostMapping("/products")
    ApiDtos.NavProductResponse createProduct(
            @Valid @RequestBody ApiDtos.CreateNavProductRequest request, @AuthenticationPrincipal CurrentUser editor) {
        NavProduct product = nav.createProduct(request.key(), request.label(), request.icon(), editor);
        return ApiDtos.NavProductResponse.of(product);
    }

    /** Renames a product, its URL slug following the new label unless that
     *  would produce the same slug it already has (task: "product rename:
     *  live header update + URL slug follows the new name"). Returns the
     *  caller's own previous key alongside the result, so a client currently
     *  showing this product can tell whether its own URL needs to move. */
    @PutMapping("/products/{key}")
    ApiDtos.UpdateNavProductResponse updateProduct(
            @PathVariable String key,
            @Valid @RequestBody ApiDtos.UpdateNavProductRequest request,
            @AuthenticationPrincipal CurrentUser editor) {
        NavService.ProductRenameResult result = nav.updateProduct(key, request.label(), request.icon(), editor);
        return ApiDtos.UpdateNavProductResponse.of(result);
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
