package com.coc.docs.nav;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Reads and writes {@code nav_product}/{@code nav_item} — the sidebar's
 * product list and each product's sub-item list.
 *
 * <p>Reordering writes every row's {@code sort_order} in the given sequence
 * rather than shifting neighbours, which is simpler to reason about than an
 * insert-at-position scheme and is cheap enough at this table's size (a
 * handful of products, at most a few dozen items each).
 */
@Repository
public class NavRepository {

    private final JdbcClient jdbc;

    public NavRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The full tree, products and their items both in sort order. */
    public List<NavProduct> findAllWithItems() {
        record ProductRow(String key, String label, String icon, int sortOrder, String minRole) {}

        List<ProductRow> products = jdbc.sql(
                        "select key, label, icon, sort_order, min_role from nav_product order by sort_order")
                .query((rs, n) -> new ProductRow(
                        rs.getString("key"),
                        rs.getString("label"),
                        rs.getString("icon"),
                        rs.getInt("sort_order"),
                        rs.getString("min_role")))
                .list();

        List<NavItem> items = jdbc.sql(
                        "select product_key, slug, label, sort_order from nav_item order by product_key, sort_order")
                .query((rs, n) -> new NavItem(
                        rs.getString("product_key"), rs.getString("slug"), rs.getString("label"), rs.getInt("sort_order")))
                .list();

        Map<String, List<NavItem>> itemsByProduct = items.stream()
                .collect(Collectors.groupingBy(NavItem::productKey, LinkedHashMap::new, Collectors.toList()));

        return products.stream()
                .map(p -> new NavProduct(
                        p.key(),
                        p.label(),
                        p.icon(),
                        p.sortOrder(),
                        p.minRole(),
                        itemsByProduct.getOrDefault(p.key(), List.of())))
                .toList();
    }

    public boolean productExists(String key) {
        Integer count = jdbc.sql("select count(*) from nav_product where key = ?").param(key).query(Integer.class).single();
        return count != null && count > 0;
    }

    /** One product's own row, with no sub-items attached — the cheap read a
     *  rename needs (its current label/icon/sortOrder/minRole), where {@link
     *  #findAllWithItems} would do unnecessary work. */
    public record ProductSnapshot(String key, String label, String icon, int sortOrder, String minRole) {}

    public Optional<ProductSnapshot> findProduct(String key) {
        return jdbc.sql("select key, label, icon, sort_order, min_role from nav_product where key = ?")
                .param(key)
                .query((rs, n) -> new ProductSnapshot(
                        rs.getString("key"),
                        rs.getString("label"),
                        rs.getString("icon"),
                        rs.getInt("sort_order"),
                        rs.getString("min_role")))
                .optional();
    }

    /** Whether {@code slug} is already spoken for — either a live product key
     *  or one retired into {@code nav_product_slug_history} — so a rename's
     *  slug-conflict check (and the "-2", "-3"... suffixing it drives) sees
     *  the same hijack-prevention rule the history table's own primary key
     *  enforces at write time. */
    public boolean slugEverUsed(String slug) {
        Integer count = jdbc.sql(
                        """
                        select count(*) from (
                            select key as slug from nav_product
                            union all
                            select old_slug as slug from nav_product_slug_history
                        ) taken where slug = ?
                        """)
                .param(slug)
                .query(Integer.class)
                .single();
        return count != null && count > 0;
    }

    /** Records the slug a product is about to be renamed off of, keyed by
     *  that old slug. Must run BEFORE {@link #renameProductKey} in the same
     *  transaction — {@code product_key} here cascades forward on every
     *  later rename only because it already points at the product by the
     *  time that rename's {@code update ... set key = ...} runs (see
     *  V79's own comment for why that makes a rename chain self-resolving). */
    public void insertSlugHistory(String oldSlug, String productKey) {
        jdbc.sql("insert into nav_product_slug_history (old_slug, product_key) values (?, ?)")
                .params(oldSlug, productKey)
                .update();
    }

    /** The product's current key, given any slug it has ever been known by —
     *  resolves straight to the latest one even across several renames,
     *  never a stale intermediate (see V79's cascade-chain comment). */
    public Optional<String> findCurrentKeyByOldSlug(String oldSlug) {
        return jdbc.sql("select product_key from nav_product_slug_history where old_slug = ?")
                .param(oldSlug)
                .query(String.class)
                .optional();
    }

    /** The one statement a slug-following rename needs on {@code
     *  nav_product} itself: {@code nav_item.product_key} and every {@code
     *  nav_product_slug_history} row already pointing at {@code oldKey}
     *  cascade to {@code newKey} as part of this same UPDATE (V79's {@code on
     *  update cascade}) — {@code docs_content}, which has no foreign key
     *  here, is the only thing a caller still has to repoint by hand (see
     *  {@code DocsContentRepository#repointProductKey}). */
    public void renameProductKey(String oldKey, String newKey, String label, String icon) {
        int updated = jdbc.sql("update nav_product set key = ?, label = ?, icon = ?, updated_at = now() where key = ?")
                .params(newKey, label, icon, oldKey)
                .update();
        if (updated == 0) {
            throw new IllegalStateException("product \"" + oldKey + "\" vanished mid-rename");
        }
    }

    public int productCount() {
        return jdbc.sql("select count(*) from nav_product").query(Integer.class).single();
    }

    public int nextProductOrder() {
        Integer max = jdbc.sql("select coalesce(max(sort_order), 0) from nav_product").query(Integer.class).single();
        return max + 1;
    }

    public void insertProduct(String key, String label, String icon, int sortOrder) {
        jdbc.sql("insert into nav_product (key, label, icon, sort_order) values (?, ?, ?, ?)")
                .params(key, label, icon, sortOrder)
                .update();
    }

    public int updateProduct(String key, String label, String icon) {
        return jdbc.sql("update nav_product set label = ?, icon = ?, updated_at = now() where key = ?")
                .params(label, icon, key)
                .update();
    }

    public int deleteProduct(String key) {
        return jdbc.sql("delete from nav_product where key = ?").param(key).update();
    }

    public boolean itemExists(String productKey, String slug) {
        Integer count = jdbc.sql("select count(*) from nav_item where product_key = ? and slug = ?")
                .params(productKey, slug)
                .query(Integer.class)
                .single();
        return count != null && count > 0;
    }

    public int itemCountForProduct(String productKey) {
        return jdbc.sql("select count(*) from nav_item where product_key = ?")
                .param(productKey)
                .query(Integer.class)
                .single();
    }

    public int nextItemOrder(String productKey) {
        Integer max = jdbc.sql("select coalesce(max(sort_order), 0) from nav_item where product_key = ?")
                .param(productKey)
                .query(Integer.class)
                .single();
        return max + 1;
    }

    public void insertItem(String productKey, String slug, String label, int sortOrder) {
        jdbc.sql("insert into nav_item (product_key, slug, label, sort_order) values (?, ?, ?, ?)")
                .params(productKey, slug, label, sortOrder)
                .update();
    }

    public int updateItem(String productKey, String slug, String label) {
        return jdbc.sql("update nav_item set label = ?, updated_at = now() where product_key = ? and slug = ?")
                .params(label, productKey, slug)
                .update();
    }

    public int deleteItem(String productKey, String slug) {
        return jdbc.sql("delete from nav_item where product_key = ? and slug = ?")
                .params(productKey, slug)
                .update();
    }

    public void reorderProducts(List<String> orderedKeys) {
        for (int i = 0; i < orderedKeys.size(); i++) {
            jdbc.sql("update nav_product set sort_order = ?, updated_at = now() where key = ?")
                    .params(i + 1, orderedKeys.get(i))
                    .update();
        }
    }

    public void reorderItems(String productKey, List<String> orderedSlugs) {
        for (int i = 0; i < orderedSlugs.size(); i++) {
            jdbc.sql("update nav_item set sort_order = ?, updated_at = now() where product_key = ? and slug = ?")
                    .params(i + 1, productKey, orderedSlugs.get(i))
                    .update();
        }
    }
}
