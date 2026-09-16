package com.coc.docs.nav;

import com.coc.docs.docscontent.DocsContentRepository;
import com.coc.docs.web.ApiException;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * Business rules for the nav structure — mainly the removal-safety and
 * key/slug validation the repository has no business knowing about.
 *
 * <p>// req: nav-structure investigation, 2026-09-15
 */
@Service
public class NavService {

    /** Kebab-case, matching every existing product key and slug (e.g.
     *  "jex3-0", "security-gates"). A sub-item's own index route is the one
     *  exception, keyed by an empty slug rather than a value here. */
    private static final Pattern KEY_PATTERN = Pattern.compile("^[a-z0-9]+(-[a-z0-9]+)*$");

    /** Every other top-level route this app already serves statically
     *  (app/login, app/settings, ...). A product created with one of these
     *  keys would be permanently shadowed by that static route and could
     *  never actually be opened, so it is refused at creation rather than
     *  left to fail silently later. */
    private static final Set<String> RESERVED_PRODUCT_KEYS = Set.of(
            "login", "register", "overview", "settings", "users-roles", "content", "welcome", "claude-usage",
            "forgot-password", "reset-password", "create-password", "docs", "auth", "api", "idea", "workflow");

    private final NavRepository repository;
    private final DocsContentRepository contentRepository;

    public NavService(NavRepository repository, DocsContentRepository contentRepository) {
        this.repository = repository;
        this.contentRepository = contentRepository;
    }

    public List<NavProduct> findAll() {
        return repository.findAllWithItems();
    }

    public NavProduct createProduct(String key, String label, String icon) {
        validateKey(key, "product key");
        if (RESERVED_PRODUCT_KEYS.contains(key)) {
            throw ApiException.badRequest("\"" + key + "\" is already a route in this app and can't be used as a product key");
        }
        if (repository.productExists(key)) {
            throw ApiException.badRequest("a product with key \"" + key + "\" already exists");
        }
        requireLabel(label);
        requireLabel(icon);
        int order = repository.nextProductOrder();
        repository.insertProduct(key, label, icon, order);
        return new NavProduct(key, label, icon, order, List.of());
    }

    public void updateProduct(String key, String label, String icon) {
        requireLabel(label);
        requireLabel(icon);
        int updated = repository.updateProduct(key, label, icon);
        if (updated == 0) {
            throw ApiException.notFound("product " + key);
        }
    }

    /**
     * Refuses to delete a product that still has sub-items — each of those
     * has to be removed first (which itself refuses while it has saved
     * content), so a product can never disappear out from under content
     * still reachable through it.
     */
    public void deleteProduct(String key) {
        if (!repository.productExists(key)) {
            throw ApiException.notFound("product " + key);
        }
        int itemCount = repository.itemCountForProduct(key);
        if (itemCount > 0) {
            throw ApiException.conflict(
                    "\"" + key + "\" still has " + itemCount + " sub-item(s) — remove those first");
        }
        repository.deleteProduct(key);
    }

    public void reorderProducts(List<String> orderedKeys) {
        List<NavProduct> current = repository.findAllWithItems();
        Set<String> currentKeys = current.stream().map(NavProduct::key).collect(java.util.stream.Collectors.toSet());
        if (orderedKeys.size() != currentKeys.size() || !currentKeys.containsAll(orderedKeys)) {
            throw ApiException.badRequest("the given order must contain exactly the current set of product keys");
        }
        repository.reorderProducts(orderedKeys);
    }

    public NavItem createItem(String productKey, String slug, String label) {
        if (!repository.productExists(productKey)) {
            throw ApiException.notFound("product " + productKey);
        }
        if (!slug.isEmpty()) {
            validateKey(slug, "slug");
        }
        if (repository.itemExists(productKey, slug)) {
            throw ApiException.badRequest("\"" + productKey + "\" already has a sub-item with that slug");
        }
        requireLabel(label);
        int order = repository.nextItemOrder(productKey);
        repository.insertItem(productKey, slug, label, order);
        return new NavItem(productKey, slug, label, order);
    }

    public void updateItem(String productKey, String slug, String label) {
        requireLabel(label);
        int updated = repository.updateItem(productKey, slug, label);
        if (updated == 0) {
            throw ApiException.notFound("item " + productKey + "/" + slug);
        }
    }

    /** Refuses to delete a sub-item that still has a saved docs_content row. */
    public void deleteItem(String productKey, String slug) {
        if (!repository.itemExists(productKey, slug)) {
            throw ApiException.notFound("item " + productKey + "/" + slug);
        }
        if (contentRepository.find(productKey, slug).isPresent()) {
            throw ApiException.conflict(
                    "\"" + productKey + "/" + slug + "\" still has saved content — delete that first");
        }
        repository.deleteItem(productKey, slug);
    }

    public void reorderItems(String productKey, List<String> orderedSlugs) {
        List<NavProduct> current = repository.findAllWithItems();
        NavProduct product = current.stream()
                .filter(p -> p.key().equals(productKey))
                .findFirst()
                .orElseThrow(() -> ApiException.notFound("product " + productKey));
        Set<String> currentSlugs =
                product.items().stream().map(NavItem::slug).collect(java.util.stream.Collectors.toSet());
        if (orderedSlugs.size() != currentSlugs.size() || !currentSlugs.containsAll(orderedSlugs)) {
            throw ApiException.badRequest("the given order must contain exactly the product's current sub-item slugs");
        }
        repository.reorderItems(productKey, orderedSlugs);
    }

    private static void validateKey(String value, String what) {
        if (!KEY_PATTERN.matcher(value).matches()) {
            throw ApiException.badRequest(what + " must be lowercase letters, digits, and hyphens only (e.g. \"jex3-0\")");
        }
    }

    private static void requireLabel(String value) {
        if (value == null || value.isBlank()) {
            throw ApiException.badRequest("label must not be blank");
        }
    }
}
