package com.coc.docs.nav;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
