package com.coc.docs.nav;

import com.coc.docs.audit.AuditAction;
import com.coc.docs.audit.AuditRepository;
import com.coc.docs.auth.CurrentUser;
import com.coc.docs.docscontent.DocsContentRepository;
import com.coc.docs.web.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
            "forgot-password", "reset-password", "create-password", "docs", "auth", "api", "idea", "workflow",
            "ax-renewal", "attendance", "home", "meeting", "my-claude-usage", "store",
            // Locale URL prefixes (ADR-116) — /km/... is Khmer, never a product.
            "en", "km", "kr");

    /** {@code /store-<number>} is a Store app's page (ADR-108) — the frontend
     *  middleware rewrites every such path before product routing sees it, so
     *  a product with a key like this could never be opened. */
    private static final Pattern STORE_APP_PAGE = Pattern.compile("store-\\d+");

    static boolean isReservedKey(String key) {
        return RESERVED_PRODUCT_KEYS.contains(key) || STORE_APP_PAGE.matcher(key).matches();
    }

    /** The ADMINISTRATION-block items a rename/move override may target —
     *  every other key is refused rather than left to accumulate junk rows
     *  (task spec: "Server validates ... permissions (never trust the UI)").
     *  Kept in step with the hrefs in {@code frontend/components/layout/
     *  nav-config.ts}'s {@code STATIC_SECTIONS} by hand, the same way {@code
     *  RESERVED_PRODUCT_KEYS} above is a manually-kept mirror of this app's
     *  static routes — this list is five items and changes about as often. */
    private static final Set<String> STATIC_ITEM_KEYS =
            Set.of("/users-roles", "/attendance", "/claude-usage", "/announcements", "/legal-pages");

    private static final int MAX_LABEL_LENGTH = 30;
    private static final int MAX_GROUP_NAME_LENGTH = 24;

    /** A product's URL slug is derived from its label and capped here (task:
     *  "product rename ... slug rules"), independently of {@code
     *  MAX_LABEL_LENGTH} — a label can be short and still slugify long (every
     *  non-alphanumeric run becomes a single hyphen, so this is rarely hit in
     *  practice, but a label of mostly punctuation could otherwise produce an
     *  unbounded key). */
    private static final int MAX_SLUG_LENGTH = 60;

    private static final Pattern SLUG_DISALLOWED_CHARS = Pattern.compile("[^a-z0-9]+");
    private static final Pattern SLUG_EDGE_DASHES = Pattern.compile("^-+|-+$");

    private final NavRepository repository;
    private final NavGroupRepository groupRepository;
    private final DocsContentRepository contentRepository;
    private final AuditRepository audit;
    private final ObjectMapper json;

    public NavService(
            NavRepository repository,
            NavGroupRepository groupRepository,
            DocsContentRepository contentRepository,
            AuditRepository audit,
            ObjectMapper json) {
        this.repository = repository;
        this.groupRepository = groupRepository;
        this.contentRepository = contentRepository;
        this.audit = audit;
        this.json = json;
    }

    public List<NavProduct> findAll() {
        return repository.findAllWithItems();
    }

    public List<NavGroup> findAllGroups() {
        return groupRepository.findAllGroups();
    }

    public List<NavItemOverride> findAllItemOverrides() {
        return groupRepository.findAllItemOverrides();
    }

    /**
     * Renames and/or moves one ADMINISTRATION-block item. Either field may
     * be omitted (passed as {@code null}) to leave it as it already is — a
     * rename sends only {@code label}, a move sends only {@code groupId}.
     * Passing neither is refused rather than silently writing a no-op row.
     */
    public NavItemOverride upsertItemOverride(String itemKey, String label, UUID groupId, CurrentUser editor) {
        if (!STATIC_ITEM_KEYS.contains(itemKey)) {
            throw ApiException.notFound("sidebar item " + itemKey);
        }
        if (label == null && groupId == null) {
            throw ApiException.badRequest("nothing to update — pass a label and/or a group id");
        }
        if (label != null) {
            requireLength(label, "label", MAX_LABEL_LENGTH);
        }
        Integer sortOrder = null;
        if (groupId != null) {
            if (groupRepository.findGroup(groupId).isEmpty()) {
                throw ApiException.notFound("group " + groupId);
            }
            sortOrder = groupRepository.nextSortOrderInGroup(groupId);
        }

        NavItemOverride updated = groupRepository.upsertItemOverride(
                itemKey, label, groupId, sortOrder, label != null, groupId != null, editor.id());

        audit.append(
                editor.id(),
                editor.email(),
                AuditAction.NAV_ITEM_OVERRIDE_UPDATE,
                AuditAction.TARGET_NAV_ITEM_OVERRIDE,
                null,
                json.createObjectNode().put("itemKey", itemKey));

        return updated;
    }

    public NavGroup createGroup(String name, CurrentUser editor) {
        String trimmed = requireLength(name, "group name", MAX_GROUP_NAME_LENGTH);
        if (groupRepository.groupNameExists(trimmed)) {
            throw ApiException.badRequest("a group named \"" + trimmed + "\" already exists");
        }
        int order = groupRepository.nextGroupOrder();
        NavGroup created = groupRepository.insertGroup(trimmed, order);
        audit.append(
                editor.id(),
                editor.email(),
                AuditAction.NAV_GROUP_CREATE,
                AuditAction.TARGET_NAV_GROUP,
                created.id(),
                json.createObjectNode().put("name", trimmed));
        return created;
    }

    public void renameGroup(UUID id, String name, CurrentUser editor) {
        NavGroup group = groupRepository.findGroup(id).orElseThrow(() -> ApiException.notFound("group " + id));
        if (group.builtin()) {
            throw ApiException.badRequest("\"" + group.name() + "\" is a built-in group and can't be renamed");
        }
        String trimmed = requireLength(name, "group name", MAX_GROUP_NAME_LENGTH);
        if (groupRepository.groupNameExistsExcluding(trimmed, id)) {
            throw ApiException.badRequest("a group named \"" + trimmed + "\" already exists");
        }
        groupRepository.renameGroup(id, trimmed);
        audit.append(
                editor.id(),
                editor.email(),
                AuditAction.NAV_GROUP_RENAME,
                AuditAction.TARGET_NAV_GROUP,
                id,
                json.createObjectNode().put("name", trimmed));
    }

    /**
     * Refuses to delete a group that still has items pointing at it — same
     * removal-safety rule {@link #deleteProduct} enforces for products, and
     * for the same reason: nothing here should ever orphan silently.
     */
    public void deleteGroup(UUID id, CurrentUser editor) {
        NavGroup group = groupRepository.findGroup(id).orElseThrow(() -> ApiException.notFound("group " + id));
        if (group.builtin()) {
            throw ApiException.badRequest("\"" + group.name() + "\" is a built-in group and can't be deleted");
        }
        int itemCount = groupRepository.itemCountForGroup(id);
        if (itemCount > 0) {
            throw ApiException.conflict("\"" + group.name() + "\" still has " + itemCount + " item(s) — move those out first");
        }
        groupRepository.deleteGroup(id);
        audit.append(
                editor.id(),
                editor.email(),
                AuditAction.NAV_GROUP_DELETE,
                AuditAction.TARGET_NAV_GROUP,
                id,
                json.createObjectNode().put("name", group.name()));
    }

    public NavProduct createProduct(String key, String label, String icon, CurrentUser editor) {
        validateKey(key, "product key");
        if (isReservedKey(key)) {
            throw ApiException.badRequest("\"" + key + "\" is already a route in this app and can't be used as a product key");
        }
        if (repository.productExists(key)) {
            throw ApiException.badRequest("a product with key \"" + key + "\" already exists");
        }
        if (repository.slugEverUsed(key)) {
            throw ApiException.badRequest(
                    "\"" + key + "\" was a previous product's URL and can't be reused (it still redirects there)");
        }
        requireLabel(label);
        requireLabel(icon);
        int order = repository.nextProductOrder();
        repository.insertProduct(key, label, icon, order);
        audit.append(
                editor.id(),
                editor.email(),
                AuditAction.NAV_PRODUCT_CREATE,
                AuditAction.TARGET_NAV_PRODUCT,
                null,
                json.createObjectNode().put("key", key).put("label", label));
        return new NavProduct(key, label, icon, order, "user", List.of());
    }

    /** A rename's result: the key it had when the caller asked for this
     *  rename (so a client can tell whether the URL it's currently showing
     *  needs to move), and the product as it stands now. {@code product.key()}
     *  equals {@code previousKey} when the new label slugified to the same
     *  key it already had (task: "same-slug renames change nothing"). */
    public record ProductRenameResult(String previousKey, NavProduct product) {}

    /**
     * Renames a product and, unlike every other rename in this file, lets its
     * URL slug follow: {@code key} changes to match the new label unless that
     * would produce the same slug it already has. Every write this makes —
     * the slug-history row, the {@code nav_product} key change (which
     * cascades into {@code nav_item} and earlier history rows, V79), and the
     * {@code docs_content} repoint/ctaHref rewrite (no foreign key, so never
     * cascaded) — happens in one transaction: a product can never be left
     * half-renamed by a failure partway through.
     */
    @Transactional
    public ProductRenameResult updateProduct(String key, String label, String icon, CurrentUser editor) {
        requireLabel(label);
        requireLabel(icon);
        NavRepository.ProductSnapshot existing =
                repository.findProduct(key).orElseThrow(() -> ApiException.notFound("product " + key));

        String newKey = resolveSlugForRename(key, label);
        if (newKey.equals(key)) {
            repository.updateProduct(key, label, icon);
            NavProduct product = new NavProduct(key, label, icon, existing.sortOrder(), existing.minRole(), List.of());
            audit.append(
                    editor.id(),
                    editor.email(),
                    AuditAction.NAV_PRODUCT_RENAME,
                    AuditAction.TARGET_NAV_PRODUCT,
                    null,
                    json.createObjectNode().put("key", key).put("label", label));
            return new ProductRenameResult(key, product);
        }

        repository.insertSlugHistory(key, key);
        repository.renameProductKey(key, newKey, label, icon);
        contentRepository.repointProductKey(key, newKey);
        contentRepository.rewriteCtaHrefPrefix(key, newKey);

        audit.append(
                editor.id(),
                editor.email(),
                AuditAction.NAV_PRODUCT_RENAME,
                AuditAction.TARGET_NAV_PRODUCT,
                null,
                json.createObjectNode().put("previousKey", key).put("key", newKey).put("label", label));

        NavProduct product = new NavProduct(newKey, label, icon, existing.sortOrder(), existing.minRole(), List.of());
        return new ProductRenameResult(key, product);
    }

    /** The product a now-retired slug belongs to, for the redirect a
     *  landing/admin route issues when it's asked for a product's old URL. */
    public Optional<String> findCurrentKeyForOldSlug(String oldSlug) {
        return repository.findCurrentKeyByOldSlug(oldSlug);
    }

    /**
     * The slug a rename to {@code label} should produce: the same base rule
     * {@link #validateKey} enforces for a hand-typed key ({@link
     * #slugify}), reserved-word-shifted and then disambiguated against every
     * key — live or retired — that isn't this product's own current one.
     * Renaming to a label that slugifies back to the current key returns
     * that key unchanged (task: "same-slug renames change nothing") without
     * ever consulting the reserved/conflict rules below — a product already
     * holding its key obviously isn't colliding with itself.
     */
    private String resolveSlugForRename(String currentKey, String label) {
        String base = slugify(label);
        if (base.equals(currentKey)) {
            return currentKey;
        }
        if (isReservedKey(base)) {
            base = withinSlugLimit(base + "-docs");
        }
        if (!slugConflicts(base, currentKey)) {
            return base;
        }
        for (int suffix = 2; ; suffix++) {
            String candidate = withinSlugLimit(base + "-" + suffix);
            if (!slugConflicts(candidate, currentKey)) {
                return candidate;
            }
        }
    }

    private boolean slugConflicts(String candidate, String currentKey) {
        return !candidate.equals(currentKey) && repository.slugEverUsed(candidate);
    }

    /** Lowercase letters/digits only, every other run of characters collapsed
     *  to a single hyphen, leading/trailing hyphens trimmed, capped at {@link
     *  #MAX_SLUG_LENGTH} (task: "Slug = lowercase name, non-alphanumerics ->
     *  '-', collapse/trim dashes ... Max 60"). A label with no alphanumeric
     *  characters at all — not refused anywhere upstream, since a label need
     *  only be non-blank — falls back to "product" rather than producing an
     *  empty key. */
    private static String slugify(String label) {
        String base = SLUG_EDGE_DASHES.matcher(SLUG_DISALLOWED_CHARS.matcher(label.toLowerCase()).replaceAll("-")).replaceAll("");
        base = withinSlugLimit(base);
        return base.isEmpty() ? "product" : base;
    }

    private static String withinSlugLimit(String slug) {
        if (slug.length() <= MAX_SLUG_LENGTH) {
            return slug;
        }
        return SLUG_EDGE_DASHES.matcher(slug.substring(0, MAX_SLUG_LENGTH)).replaceAll("");
    }

    /**
     * Refuses to delete a product that still has sub-items — each of those
     * has to be removed first (which cascades to its own saved content, if
     * any — see {@link #deleteItem}), so a product can never disappear out
     * from under content still reachable through it.
     */
    public void deleteProduct(String key, CurrentUser editor) {
        if (!repository.productExists(key)) {
            throw ApiException.notFound("product " + key);
        }
        int itemCount = repository.itemCountForProduct(key);
        if (itemCount > 0) {
            throw ApiException.conflict(
                    "\"" + key + "\" still has " + itemCount + " sub-item(s) — remove those first");
        }
        repository.deleteProduct(key);
        audit.append(
                editor.id(),
                editor.email(),
                AuditAction.NAV_PRODUCT_DELETE,
                AuditAction.TARGET_NAV_PRODUCT,
                null,
                json.createObjectNode().put("key", key));
    }

    public void reorderProducts(List<String> orderedKeys) {
        List<NavProduct> current = repository.findAllWithItems();
        Set<String> currentKeys = current.stream().map(NavProduct::key).collect(java.util.stream.Collectors.toSet());
        if (orderedKeys.size() != currentKeys.size() || !currentKeys.containsAll(orderedKeys)) {
            throw ApiException.badRequest("the given order must contain exactly the current set of product keys");
        }
        repository.reorderProducts(orderedKeys);
    }

    public NavItem createItem(String productKey, String slug, String label, CurrentUser editor) {
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
        audit.append(
                editor.id(),
                editor.email(),
                AuditAction.NAV_ITEM_CREATE,
                AuditAction.TARGET_NAV_ITEM,
                null,
                json.createObjectNode().put("productKey", productKey).put("slug", slug).put("label", label));
        return new NavItem(productKey, slug, label, order);
    }

    public void updateItem(String productKey, String slug, String label) {
        requireLabel(label);
        int updated = repository.updateItem(productKey, slug, label);
        if (updated == 0) {
            throw ApiException.notFound("item " + productKey + "/" + slug);
        }
    }

    /**
     * Removing a sub-item also removes its saved content, if any — the two
     * are inseparable from an admin's point of view (there is no separate
     * "delete content" action anywhere in this app), and leaving the content
     * row behind would just orphan it under a slug nothing points at anymore.
     */
    public void deleteItem(String productKey, String slug, CurrentUser editor) {
        if (!repository.itemExists(productKey, slug)) {
            throw ApiException.notFound("item " + productKey + "/" + slug);
        }
        contentRepository.delete(productKey, slug);
        repository.deleteItem(productKey, slug);
        audit.append(
                editor.id(),
                editor.email(),
                AuditAction.NAV_ITEM_DELETE,
                AuditAction.TARGET_NAV_ITEM,
                null,
                json.createObjectNode().put("productKey", productKey).put("slug", slug));
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

    /** Trims and validates a 1..{@code maxLength}-character field, returning
     *  the trimmed value — the override table stores the trimmed form, never
     *  surrounding whitespace a caller happened to send. */
    private static String requireLength(String value, String what, int maxLength) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty()) {
            throw ApiException.badRequest(what + " must not be blank");
        }
        if (trimmed.length() > maxLength) {
            throw ApiException.badRequest(what + " must be " + maxLength + " characters or fewer");
        }
        return trimmed;
    }
}
