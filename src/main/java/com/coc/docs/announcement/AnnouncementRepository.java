package com.coc.docs.announcement;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Reads and writes {@code announcement} rows (V69).
 *
 * <p>Status transitions are conditional updates — {@code where status = ...}
 * — rather than read-then-write, so two admins publishing the same draft at
 * once produce one publish and one "nothing changed", not two broadcasts.
 *
 * <p>// req: ADR-098
 */
@Repository
public class AnnouncementRepository {

    /**
     * Names are a live join, not stored copies: a renamed admin shows under
     * their current name, and a deleted one shows as nobody rather than as a
     * stale name with no account behind it.
     */
    private static final String SELECT = """
            select a.id, a.title, a.body, a.category, a.status, a.expires_at, a.version_no,
                   a.published_at, pub.display_name as published_by_name,
                   a.created_at, a.updated_at, upd.display_name as updated_by_name
            from announcement a
            left join account pub on pub.id = a.published_by
            left join account upd on upd.id = a.updated_by
            """;

    private final JdbcClient jdbc;

    public AnnouncementRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static Announcement map(ResultSet rs, int rowNum) throws SQLException {
        return new Announcement(
                rs.getObject("id", UUID.class),
                rs.getString("title"),
                rs.getString("body"),
                rs.getString("category"),
                rs.getString("status"),
                instant(rs, "expires_at"),
                rs.getInt("version_no"),
                instant(rs, "published_at"),
                rs.getString("published_by_name"),
                instant(rs, "created_at"),
                instant(rs, "updated_at"),
                rs.getString("updated_by_name"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    /** Every announcement in every state, newest first — the admin list. */
    public List<Announcement> findAll() {
        return jdbc.sql(SELECT + " order by a.created_at desc").query(AnnouncementRepository::map).list();
    }

    public Optional<Announcement> findById(UUID id) {
        return jdbc.sql(SELECT + " where a.id = ?").param(id).query(AnnouncementRepository::map).optional();
    }

    /**
     * What a reader sees: published and not expired at {@code now}, most
     * recently published first.
     */
    public List<Announcement> findLive(Instant now, int limit) {
        return jdbc.sql(SELECT + """
                         where a.status = 'published'
                           and (a.expires_at is null or a.expires_at > ?)
                         order by a.published_at desc
                         limit ?
                        """)
                .params(utc(now), limit)
                .query(AnnouncementRepository::map)
                .list();
    }

    public UUID insert(String title, String body, String category, Instant expiresAt, UUID createdBy) {
        return jdbc.sql("""
                        insert into announcement (title, body, category, expires_at, created_by, updated_by)
                        values (?, ?, ?, ?, ?, ?)
                        returning id
                        """)
                .params(title, body, category, utc(expiresAt), createdBy, createdBy)
                .query(UUID.class)
                .single();
    }

    /**
     * Replaces the editable fields if, and only if, the row is still at
     * {@code expectedVersion}.
     *
     * @return false when the row is missing or has moved on; the caller tells
     *     those apart with {@link #findById}
     */
    public boolean update(
            UUID id,
            int expectedVersion,
            String title,
            String body,
            String category,
            Instant expiresAt,
            UUID updatedBy,
            Instant now) {
        return jdbc.sql("""
                        update announcement
                        set title = ?, body = ?, category = ?, expires_at = ?,
                            updated_by = ?, updated_at = ?, version_no = version_no + 1
                        where id = ? and version_no = ?
                        """)
                .params(title, body, category, utc(expiresAt), updatedBy, utc(now), id, expectedVersion)
                .update()
                > 0;
    }

    /**
     * Draft or archived to published.
     *
     * @return false when it was already published, or is gone — either way
     *     nothing went out, and the caller must not broadcast
     */
    public boolean publish(UUID id, UUID publishedBy, Instant now) {
        return jdbc.sql("""
                        update announcement
                        set status = 'published', published_at = ?, published_by = ?,
                            updated_by = ?, updated_at = ?, version_no = version_no + 1
                        where id = ? and status in ('draft', 'archived')
                        """)
                .params(utc(now), publishedBy, publishedBy, utc(now), id)
                .update()
                > 0;
    }

    /** Published to archived. False when it was not published. */
    public boolean unpublish(UUID id, UUID updatedBy, Instant now) {
        return jdbc.sql("""
                        update announcement
                        set status = 'archived', updated_by = ?, updated_at = ?, version_no = version_no + 1
                        where id = ? and status = 'published'
                        """)
                .params(updatedBy, utc(now), id)
                .update()
                > 0;
    }

    public boolean delete(UUID id) {
        return jdbc.sql("delete from announcement where id = ?").param(id).update() > 0;
    }
}
