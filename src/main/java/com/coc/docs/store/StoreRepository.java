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
 * Reads and writes {@code store_app} and {@code store_app_release} rows (V82).
 * Knows nothing about the files on disk — {@link StoreFileStorage} does.
 *
 * <p>// req: ADR-108
 */
@Repository
public class StoreRepository {

    private static final String APP_SELECT = """
            select id, app_no, name, description, platform, is_public, logo_path, logo_type, created_at, updated_at
            from store_app
            """;

    private static final String RELEASE_SELECT = """
            select id, app_id, version, environment, notes, file_name, file_path, size_bytes, sha256, created_at
            from store_app_release
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
                StorePlatform.parse(rs.getString("platform")).orElseThrow(),
                rs.getBoolean("is_public"),
                rs.getString("logo_path"),
                rs.getString("logo_type"),
                instant(rs, "created_at"),
                instant(rs, "updated_at"));
    }

    private static StoreRelease mapRelease(ResultSet rs, int rowNum) throws SQLException {
        return new StoreRelease(
                rs.getObject("id", UUID.class),
                rs.getObject("app_id", UUID.class),
                rs.getString("version"),
                StoreEnvironment.parse(rs.getString("environment")).orElseThrow(),
                rs.getString("notes"),
                rs.getString("file_name"),
                rs.getString("file_path"),
                rs.getLong("size_bytes"),
                rs.getString("sha256"),
                instant(rs, "created_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

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

    /** Every release of the given apps, newest first. */
    public List<StoreRelease> findReleases(List<UUID> appIds) {
        if (appIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql(RELEASE_SELECT + " where app_id = any(?) order by created_at desc")
                .param(appIds.toArray(UUID[]::new))
                .query(StoreRepository::mapRelease)
                .list();
    }

    public Optional<StoreRelease> findRelease(UUID id) {
        return jdbc.sql(RELEASE_SELECT + " where id = ?").param(id).query(StoreRepository::mapRelease).optional();
    }

    public boolean releaseExists(UUID appId, String version, StoreEnvironment environment) {
        return jdbc.sql("select exists (select 1 from store_app_release where app_id = ? and version = ? and environment = ?)")
                .params(appId, version, environment.wire())
                .query(Boolean.class)
                .single();
    }

    public UUID insertApp(String name, String description, StorePlatform platform, boolean isPublic, UUID actor) {
        return jdbc.sql("""
                        insert into store_app (name, description, platform, is_public, created_by, updated_by)
                        values (?, ?, ?, ?, ?, ?)
                        returning id
                        """)
                .params(name, description, platform.wire(), isPublic, actor, actor)
                .query(UUID.class)
                .single();
    }

    /** Platform is fixed once set: its releases sit in that platform's folder. */
    public boolean updateApp(UUID id, String name, String description, boolean isPublic, UUID actor) {
        return jdbc.sql("""
                        update store_app
                        set name = ?, description = ?, is_public = ?, updated_at = now(), updated_by = ?
                        where id = ?
                        """)
                .params(name, description, isPublic, actor, id)
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

    /** Releases go with it (on delete cascade). */
    public boolean deleteApp(UUID id) {
        return jdbc.sql("delete from store_app where id = ?").param(id).update() == 1;
    }

    public UUID insertRelease(
            UUID appId,
            String version,
            StoreEnvironment environment,
            String notes,
            String fileName,
            String filePath,
            long sizeBytes,
            String sha256,
            UUID actor) {
        return jdbc.sql("""
                        insert into store_app_release
                          (app_id, version, environment, notes, file_name, file_path, size_bytes, sha256, created_by)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        returning id
                        """)
                .params(appId, version, environment.wire(), notes, fileName, filePath, sizeBytes, sha256, actor)
                .query(UUID.class)
                .single();
    }

    public boolean deleteRelease(UUID id) {
        return jdbc.sql("delete from store_app_release where id = ?").param(id).update() == 1;
    }

    /** Touches the app so "last updated" moves when a build is added or removed. */
    public void touchApp(UUID id, UUID actor) {
        jdbc.sql("update store_app set updated_at = now(), updated_by = ? where id = ?")
                .params(actor, id)
                .update();
    }
}
