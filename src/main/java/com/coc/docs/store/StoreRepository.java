package com.coc.docs.store;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Reads and writes {@code store_app} (V82, V85) and {@code store_build} (V85)
 * rows. Knows nothing about the files on disk — {@link StoreFileStorage} does.
 *
 * <p>// req: ADR-108, ADR-111, ADR-112
 */
@Repository
public class StoreRepository {

    private static final String APP_SELECT = """
            select id, app_no, name, description, category, is_public, logo_path, logo_type, banner_path, banner_type,
                   created_at, updated_at
            from store_app
            """;

    private static final String BUILD_SELECT = """
            select id, app_id, platform, version, environment, notes, file_name, file_path, size_bytes, sha256,
                   created_at, removed_at,
                   (select a.display_name from account a where a.id = store_build.created_by) as uploaded_by
            from store_build
            """;

    private final JdbcClient jdbc;

    public StoreRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static StoreApp mapApp(ResultSet rs, int rowNum) throws SQLException {
        return new StoreApp(
                rs.getObject("id", UUID.class),
                rs.getLong("app_no"),
                rs.getString("name"),
                rs.getString("description"),
                StoreCategory.parse(rs.getString("category")).orElseThrow(),
                rs.getBoolean("is_public"),
                rs.getString("logo_path"),
                rs.getString("logo_type"),
                rs.getString("banner_path"),
                rs.getString("banner_type"),
                instant(rs, "created_at"),
                instant(rs, "updated_at"));
    }

    private static StoreBuild mapBuild(ResultSet rs, int rowNum) throws SQLException {
        return new StoreBuild(
                rs.getObject("id", UUID.class),
                rs.getObject("app_id", UUID.class),
                StorePlatform.parse(rs.getString("platform")).orElseThrow(),
                rs.getString("version"),
                StoreEnvironment.parse(rs.getString("environment")).orElseThrow(),
                rs.getString("notes"),
                rs.getString("file_name"),
                rs.getString("file_path"),
                rs.getLong("size_bytes"),
                rs.getString("sha256"),
                instant(rs, "created_at"),
                instant(rs, "removed_at"),
                rs.getString("uploaded_by"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    // --- apps -------------------------------------------------------------------

    /** Every app, or only the public ones — alphabetical, as the page lists them. */
    public List<StoreApp> findApps(boolean publicOnly) {
        String where = publicOnly ? " where is_public" : "";
        return jdbc.sql(APP_SELECT + where + " order by lower(name), created_at")
                .query(StoreRepository::mapApp)
                .list();
    }

    public Optional<StoreApp> findApp(UUID id) {
        return jdbc.sql(APP_SELECT + " where id = ?").param(id).query(StoreRepository::mapApp).optional();
    }

    /** By the number in the app's page URL ({@code /store-<number>}). */
    public Optional<StoreApp> findAppByNumber(long number) {
        return jdbc.sql(APP_SELECT + " where app_no = ?").param(number).query(StoreRepository::mapApp).optional();
    }

    public UUID insertApp(String name, String description, StoreCategory category, boolean isPublic, UUID actor) {
        return jdbc.sql("""
                        insert into store_app (name, description, category, is_public, created_by, updated_by)
                        values (?, ?, ?, ?, ?, ?)
                        returning id
                        """)
                .params(name, description, category.wire(), isPublic, actor, actor)
                .query(UUID.class)
                .single();
    }

    public boolean updateApp(UUID id, String name, String description, StoreCategory category, boolean isPublic, UUID actor) {
        return jdbc.sql("""
                        update store_app
                        set name = ?, description = ?, category = ?, is_public = ?, updated_at = now(), updated_by = ?
                        where id = ?
                        """)
                .params(name, description, category.wire(), isPublic, actor, id)
                .update() == 1;
    }

    public void setLogo(UUID id, String logoPath, String logoType, UUID actor) {
        jdbc.sql("""
                        update store_app
                        set logo_path = ?, logo_type = ?, updated_at = now(), updated_by = ?
                        where id = ?
                        """)
                .params(logoPath, logoType, actor, id)
                .update();
    }

    /** Sets or clears ({@code null}s) an app's banner picture (V87, ADR-117). */
    public void setBanner(UUID id, String bannerPath, String bannerType, UUID actor) {
        jdbc.sql("""
                        update store_app
                        set banner_path = ?, banner_type = ?, updated_at = now(), updated_by = ?
                        where id = ?
                        """)
                .params(bannerPath, bannerType, actor, id)
                .update();
    }

    // --- settings (V87) ---------------------------------------------------------

    /** The app an admin chose for the Store's banner, or empty for "the latest automatically". */
    public Optional<UUID> findFeaturedAppId() {
        return jdbc.sql("select featured_app_id from store_settings")
                .query((rs, rowNum) -> rs.getObject("featured_app_id", UUID.class))
                .optional()
                .flatMap(Optional::ofNullable);
    }

    /** {@code null} goes back to "the latest automatically". */
    public void setFeaturedAppId(UUID appId, UUID actor) {
        jdbc.sql("update store_settings set featured_app_id = ?, updated_at = now(), updated_by = ?")
                .params(appId, actor)
                .update();
    }

    /** Its builds go with it (on delete cascade). */
    public boolean deleteApp(UUID id) {
        return jdbc.sql("delete from store_app where id = ?").param(id).update() == 1;
    }

    /** Touches the app so "last updated" moves when a build is added or removed. */
    public void touchApp(UUID id, UUID actor) {
        jdbc.sql("update store_app set updated_at = now(), updated_by = ? where id = ?")
                .params(actor, id)
                .update();
    }

    /**
     * Whether another app already has this name, ignoring case and
     * surrounding spaces. An app spans platforms (ADR-111), so a name is
     * unique across the whole Store.
     */
    public boolean appNameTaken(String name, UUID excludeId) {
        return jdbc.sql("""
                        select exists (select 1 from store_app
                                       where lower(btrim(name)) = lower(btrim(?))
                                         and (?::uuid is null or id <> ?::uuid))
                        """)
                .params(name, excludeId, excludeId)
                .query(Boolean.class)
                .single();
    }

    // --- builds -----------------------------------------------------------------

    /** Every build of the given apps, removed ones included, newest first. */
    public List<StoreBuild> findBuilds(List<UUID> appIds) {
        if (appIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql(BUILD_SELECT + " where app_id = any(?) order by created_at desc")
                .param(appIds.toArray(UUID[]::new))
                .query(StoreRepository::mapBuild)
                .list();
    }

    public Optional<StoreBuild> findBuild(UUID id) {
        return jdbc.sql(BUILD_SELECT + " where id = ?").param(id).query(StoreRepository::mapBuild).optional();
    }

    /** Whether a live build of this platform and version exists in this environment. A removed one does not count. */
    public boolean liveBuildExists(UUID appId, StorePlatform platform, String version, StoreEnvironment environment) {
        return jdbc.sql("""
                        select exists (select 1 from store_build
                                       where app_id = ? and platform = ? and version = ? and environment = ?
                                         and removed_at is null)
                        """)
                .params(appId, platform.wire(), version, environment.wire())
                .query(Boolean.class)
                .single();
    }

    public UUID insertBuild(
            UUID appId,
            StorePlatform platform,
            String version,
            StoreEnvironment environment,
            String notes,
            String fileName,
            String filePath,
            long sizeBytes,
            String sha256,
            UUID actor) {
        return jdbc.sql("""
                        insert into store_build
                          (app_id, platform, version, environment, notes, file_name, file_path, size_bytes, sha256,
                           created_by)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        returning id
                        """)
                .params(appId, platform.wire(), version, environment.wire(), notes, fileName, filePath, sizeBytes,
                        sha256, actor)
                .query(UUID.class)
                .single();
    }

    /**
     * Marks a live build removed. The row stays so the history can say
     * "Removed"; the caller deletes the file. False if it was already removed —
     * two admins removing at once produce one removal.
     */
    public boolean markBuildRemoved(UUID id, UUID actor) {
        return jdbc.sql("update store_build set removed_at = now(), removed_by = ? where id = ? and removed_at is null")
                .params(actor, id)
                .update() == 1;
    }
}
