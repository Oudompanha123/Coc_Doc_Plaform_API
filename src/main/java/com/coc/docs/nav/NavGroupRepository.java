package com.coc.docs.nav;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Reads and writes {@code nav_group}/{@code nav_item_override} — the
 * sidebar's custom groups and each ADMINISTRATION-block item's rename/move
 * override. See {@code V77__nav_item_override.sql} for why these are
 * separate from {@code nav_product}/{@code nav_item} ({@link NavRepository}).
 */
@Repository
public class NavGroupRepository {

    private final JdbcClient jdbc;

    public NavGroupRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static final String GROUP_COLUMNS = "id, name, is_builtin, sort_order";

    public List<NavGroup> findAllGroups() {
        return jdbc.sql("select " + GROUP_COLUMNS + " from nav_group order by sort_order")
                .query(NavGroupRepository::mapGroup)
                .list();
    }

    public Optional<NavGroup> findGroup(UUID id) {
        return jdbc.sql("select " + GROUP_COLUMNS + " from nav_group where id = ?")
                .param(id)
                .query(NavGroupRepository::mapGroup)
                .optional();
    }

    public boolean groupNameExists(String name) {
        Integer count = jdbc.sql("select count(*) from nav_group where lower(name) = lower(?)")
                .param(name)
                .query(Integer.class)
                .single();
        return count != null && count > 0;
    }

    public boolean groupNameExistsExcluding(String name, UUID excludingId) {
        Integer count = jdbc.sql("select count(*) from nav_group where lower(name) = lower(?) and id != ?")
                .params(name, excludingId)
                .query(Integer.class)
                .single();
        return count != null && count > 0;
    }

    public int nextGroupOrder() {
        Integer max = jdbc.sql("select coalesce(max(sort_order), 0) from nav_group").query(Integer.class).single();
        return max + 1;
    }

    public NavGroup insertGroup(String name, int sortOrder) {
        return jdbc.sql("""
                        insert into nav_group (name, is_builtin, sort_order)
                        values (?, false, ?)
                        returning\s""" + GROUP_COLUMNS)
                .params(name, sortOrder)
                .query(NavGroupRepository::mapGroup)
                .single();
    }

    public int renameGroup(UUID id, String name) {
        return jdbc.sql("update nav_group set name = ?, updated_at = now() where id = ?")
                .params(name, id)
                .update();
    }

    public int deleteGroup(UUID id) {
        return jdbc.sql("delete from nav_group where id = ?").param(id).update();
    }

    public int itemCountForGroup(UUID groupId) {
        return jdbc.sql("select count(*) from nav_item_override where group_id = ?")
                .param(groupId)
                .query(Integer.class)
                .single();
    }

    /** The next sort position in a group — "moved item goes to the end of
     *  the target group". Only accounts for other items already moved there
     *  via an override row; the code-defined default members of a built-in
     *  group (ADMINISTRATION's own five, in their fixed order) are always
     *  treated as sorting before any moved-in item, so a plain {@code 1} for
     *  an empty group is the correct starting point either way. */
    public int nextSortOrderInGroup(UUID groupId) {
        Integer max = jdbc.sql("select coalesce(max(sort_order), 0) from nav_item_override where group_id = ?")
                .param(groupId)
                .query(Integer.class)
                .single();
        return max + 1;
    }

    private static NavGroup mapGroup(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new NavGroup(
                rs.getObject("id", UUID.class),
                rs.getString("name"),
                rs.getBoolean("is_builtin"),
                rs.getInt("sort_order"));
    }

    // -------------------------------------------------------------------
    // Item overrides
    // -------------------------------------------------------------------

    private static final String OVERRIDE_COLUMNS = "item_key, custom_label, group_id, sort_order";

    public List<NavItemOverride> findAllItemOverrides() {
        return jdbc.sql("select " + OVERRIDE_COLUMNS + " from nav_item_override")
                .query(NavGroupRepository::mapOverride)
                .list();
    }

    public Optional<NavItemOverride> findItemOverride(String itemKey) {
        return jdbc.sql("select " + OVERRIDE_COLUMNS + " from nav_item_override where item_key = ?")
                .param(itemKey)
                .query(NavGroupRepository::mapOverride)
                .optional();
    }

    /**
     * Inserts or updates the override row for one item. {@code label}/{@code
     * groupId} are each applied only when non-null — the caller (a rename
     * sends a label, a move sends a group id + its target sort order) passes
     * the other as {@code null} to mean "leave this field as it already is",
     * using {@code case} against the existing row rather than overwriting it
     * with null on an upsert that only meant to change one field.
     */
    public NavItemOverride upsertItemOverride(
            String itemKey, String label, UUID groupId, Integer sortOrder, boolean hasLabel, boolean hasGroup, UUID updatedBy) {
        return jdbc.sql("""
                        insert into nav_item_override (item_key, custom_label, group_id, sort_order, updated_at, updated_by)
                        values (?, ?, ?, ?, now(), ?)
                        on conflict (item_key) do update
                        set custom_label = case when ? then excluded.custom_label else nav_item_override.custom_label end,
                            group_id = case when ? then excluded.group_id else nav_item_override.group_id end,
                            sort_order = case when ? then excluded.sort_order else nav_item_override.sort_order end,
                            updated_at = now(),
                            updated_by = excluded.updated_by
                        returning\s""" + OVERRIDE_COLUMNS)
                .params(itemKey, label, groupId, sortOrder, updatedBy, hasLabel, hasGroup, hasGroup)
                .query(NavGroupRepository::mapOverride)
                .single();
    }

    private static NavItemOverride mapOverride(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new NavItemOverride(
                rs.getString("item_key"),
                rs.getString("custom_label"),
                (UUID) rs.getObject("group_id"),
                rs.getObject("sort_order") == null ? null : rs.getInt("sort_order"));
    }
}
