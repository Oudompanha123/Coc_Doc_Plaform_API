package com.coc.docs.legal;

import com.coc.docs.page.VersionStatus;
import com.coc.docs.web.Jsonb;
import com.fasterxml.jackson.databind.JsonNode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Reads and writes {@code legal_page_version} rows.
 *
 * <p>Draft and published are separate rows, not a flag, mirroring {@code
 * PageVersionRepository}: two partial unique indexes hold at most one
 * published row and at most one draft row per {@code slug} at all times, not
 * merely at commit — neither is deferrable, so every statement here is
 * written to keep both satisfied throughout a transaction.
 */
@Repository
public class LegalPageVersionRepository {

    private static final String COLUMNS = """
            id, slug, status, content::text as content, version_no,
            published_at, published_by, publish_note, created_at, updated_at
            """;

    private final JdbcClient jdbc;
    private final Jsonb jsonb;
    private final RowMapper<LegalPageVersion> mapper;

    public LegalPageVersionRepository(JdbcClient jdbc, Jsonb jsonb) {
        this.jdbc = jdbc;
        this.jsonb = jsonb;
        this.mapper = this::map;
    }

    private LegalPageVersion map(ResultSet rs, int rowNum) throws SQLException {
        OffsetDateTime publishedAt = rs.getObject("published_at", OffsetDateTime.class);
        return new LegalPageVersion(
                rs.getObject("id", UUID.class),
                require(LegalSlug.parse(rs.getString("slug")), "slug", rs.getString("slug")),
                require(VersionStatus.parse(rs.getString("status")), "status", rs.getString("status")),
                jsonb.read(rs.getString("content")),
                rs.getInt("version_no"),
                publishedAt == null ? null : publishedAt.toInstant(),
                rs.getObject("published_by", UUID.class),
                rs.getString("publish_note"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getObject("updated_at", OffsetDateTime.class).toInstant());
    }

    private static <T> T require(Optional<T> value, String column, String raw) throws SQLException {
        if (value.isEmpty()) {
            throw new SQLException(
                    "legal_page_version." + column + " holds a value the application cannot parse: " + raw);
        }
        return value.get();
    }

    // -----------------------------------------------------------------------
    // Reads
    // -----------------------------------------------------------------------

    public Optional<LegalPageVersion> find(LegalSlug slug, VersionStatus status) {
        return jdbc.sql("select " + COLUMNS + """
                         from legal_page_version
                         where slug = ? and status = ?
                        """)
                .params(slug.wire(), status.wire())
                .query(mapper)
                .optional();
    }

    public Optional<LegalPageVersion> findPublished(LegalSlug slug) {
        return find(slug, VersionStatus.PUBLISHED);
    }

    public Optional<LegalPageVersion> findDraft(LegalSlug slug) {
        return find(slug, VersionStatus.DRAFT);
    }

    public Optional<LegalPageVersion> findById(UUID id) {
        return jdbc.sql("select " + COLUMNS + " from legal_page_version where id = ?")
                .param(id)
                .query(mapper)
                .optional();
    }

    /**
     * Published/archived rows for one slug, newest first — "published
     * versions" history. The live draft never appears here; it is not a
     * version anyone has published yet.
     */
    public List<LegalPageVersion> history(LegalSlug slug) {
        return jdbc.sql("select " + COLUMNS + """
                         from legal_page_version
                         where slug = ? and status in ('published', 'archived')
                         order by coalesce(published_at, created_at) desc
                        """)
                .param(slug.wire())
                .query(mapper)
                .list();
    }

    // -----------------------------------------------------------------------
    // Writes
    // -----------------------------------------------------------------------

    /**
     * Creates a draft row, inheriting {@code versionNo} from whatever it was
     * branched from so the optimistic lock starts from a number the editor
     * actually saw (same reasoning as {@code PageVersionRepository.insertDraft}).
     */
    public LegalPageVersion insertDraft(LegalSlug slug, JsonNode content, int versionNo) {
        return jdbc.sql("""
                        insert into legal_page_version (slug, status, content, version_no)
                        values (?, 'draft', ?::jsonb, ?)
                        returning """ + COLUMNS)
                .params(slug.wire(), jsonb.write(content), versionNo)
                .query(mapper)
                .single();
    }

    /**
     * Updates a draft only if its {@code version_no} still matches what the
     * editor loaded, and bumps it on success. An empty result is a conflict,
     * not a missing row — the caller answers 409 (FR-CONT-013's pattern).
     */
    public Optional<LegalPageVersion> updateDraft(UUID versionId, int expectedVersionNo, JsonNode content) {
        return jdbc.sql("""
                        update legal_page_version
                        set content = ?::jsonb, version_no = version_no + 1, updated_at = now()
                        where id = ? and version_no = ? and status = 'draft'
                        returning """ + COLUMNS)
                .params(jsonb.write(content), versionId, expectedVersionNo)
                .query(mapper)
                .optional();
    }

    /**
     * Retires the currently published row, if there is one. Must run before
     * a draft is promoted — the one-published-row index is not deferrable.
     */
    public int archivePublished(LegalSlug slug) {
        return jdbc.sql("""
                        update legal_page_version
                        set status = 'archived', updated_at = now()
                        where slug = ? and status = 'published'
                        """)
                .param(slug.wire())
                .update();
    }

    /**
     * Overwrites the current draft's content from an old version (Restore).
     * Never touches {@code status} — this never publishes by itself, only
     * replaces what the next publish would promote.
     */
    public Optional<LegalPageVersion> restoreContent(UUID draftId, JsonNode content) {
        return jdbc.sql("""
                        update legal_page_version
                        set content = ?::jsonb, version_no = version_no + 1, updated_at = now()
                        where id = ? and status = 'draft'
                        returning """ + COLUMNS)
                .params(jsonb.write(content), draftId)
                .query(mapper)
                .optional();
    }

    /** Promotes a draft to published, recording when, by whom, and (optionally) why. */
    public Optional<LegalPageVersion> promoteDraft(UUID versionId, UUID publishedBy, String publishNote) {
        return jdbc.sql("""
                        update legal_page_version
                        set status = 'published', published_at = now(), published_by = ?,
                            publish_note = ?, updated_at = now()
                        where id = ? and status = 'draft'
                        returning """ + COLUMNS)
                .params(publishedBy, publishNote, versionId)
                .query(mapper)
                .optional();
    }
}
