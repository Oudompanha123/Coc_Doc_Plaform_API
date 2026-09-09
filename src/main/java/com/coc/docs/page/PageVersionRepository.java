package com.coc.docs.page;

import com.coc.docs.content.Locale;
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
 * Reads and writes {@code page_version} rows.
 *
 * <p>Draft and published are separate rows, not a flag, so a draft save cannot
 * touch what readers see. Two partial unique indexes in the schema hold that
 * shape: at most one published row and at most one draft row per
 * {@code (page, locale)}. Every statement here is written to keep those indexes
 * satisfied at all times, not merely at commit — they are not deferrable.
 *
 * <p>// req: FR-CONT-003, FR-CONT-005, FR-CONT-012, FR-CONT-013
 */
@Repository
public class PageVersionRepository {

    private static final String COLUMNS = """
            id, page_id, locale, status, title, description, body::text as body,
            version_no, source_version_id, created_by, created_at, updated_at
            """;

    private final JdbcClient jdbc;
    private final Jsonb jsonb;
    private final RowMapper<PageVersion> mapper;

    public PageVersionRepository(JdbcClient jdbc, Jsonb jsonb) {
        this.jdbc = jdbc;
        this.jsonb = jsonb;
        this.mapper = this::map;
    }

    private PageVersion map(ResultSet rs, int rowNum) throws SQLException {
        return new PageVersion(
                rs.getObject("id", UUID.class),
                rs.getObject("page_id", UUID.class),
                require(Locale.parse(rs.getString("locale")), "locale", rs.getString("locale")),
                require(
                        VersionStatus.parse(rs.getString("status")),
                        "status",
                        rs.getString("status")),
                rs.getString("title"),
                rs.getString("description"),
                jsonb.read(rs.getString("body")),
                rs.getInt("version_no"),
                rs.getObject("source_version_id", UUID.class),
                rs.getObject("created_by", UUID.class),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getObject("updated_at", OffsetDateTime.class).toInstant());
    }

    private static <T> T require(Optional<T> value, String column, String raw) throws SQLException {
        if (value.isEmpty()) {
            throw new SQLException(
                    "page_version." + column + " holds a value the application cannot parse: " + raw);
        }
        return value.get();
    }

    // -----------------------------------------------------------------------
    // Reads
    // -----------------------------------------------------------------------

    public Optional<PageVersion> findById(UUID id) {
        return jdbc.sql("select " + COLUMNS + " from page_version where id = ?")
                .param(id)
                .query(mapper)
                .optional();
    }

    public Optional<PageVersion> find(UUID pageId, Locale locale, VersionStatus status) {
        return jdbc.sql("select " + COLUMNS + """
                         from page_version
                         where page_id = ? and locale = ?::locale and status = ?::version_status
                        """)
                .params(pageId, locale.wire(), status.wire())
                .query(mapper)
                .optional();
    }

    public Optional<PageVersion> findPublished(UUID pageId, Locale locale) {
        return find(pageId, locale, VersionStatus.PUBLISHED);
    }

    public Optional<PageVersion> findDraft(UUID pageId, Locale locale) {
        return find(pageId, locale, VersionStatus.DRAFT);
    }

    /**
     * Full history for one locale, newest first.
     *
     * <p>// req: FR-CONT-012
     */
    public List<PageVersion> history(UUID pageId, Locale locale) {
        return jdbc.sql("select " + COLUMNS + """
                         from page_version
                         where page_id = ? and locale = ?::locale
                         order by updated_at desc, created_at desc
                        """)
                .params(pageId, locale.wire())
                .query(mapper)
                .list();
    }

    // -----------------------------------------------------------------------
    // Writes
    // -----------------------------------------------------------------------

    /**
     * Creates a draft row.
     *
     * <p>{@code versionNo} starts at the value the caller supplies rather than
     * always at 1, so that a draft branched from a published version inherits
     * that version number. The optimistic-lock comparison is then against a
     * number the editor actually saw.
     *
     * <p>// req: FR-CONT-003, FR-CONT-013
     */
    public PageVersion insertDraft(
            UUID pageId,
            Locale locale,
            String title,
            String description,
            JsonNode body,
            int versionNo,
            UUID sourceVersionId,
            UUID createdBy) {

        return jdbc.sql("""
                        insert into page_version
                          (page_id, locale, status, title, description, body, version_no,
                           source_version_id, created_by)
                        values (?, ?::locale, 'draft', ?, ?, ?::jsonb, ?, ?, ?)
                        returning id, page_id, locale, status, title, description, body::text as body,
                                  version_no, source_version_id, created_by, created_at, updated_at
                        """)
                .params(
                        pageId,
                        locale.wire(),
                        title,
                        description,
                        jsonb.write(body),
                        versionNo,
                        sourceVersionId,
                        createdBy)
                .query(mapper)
                .single();
    }

    /**
     * Updates a draft only if its {@code version_no} still matches what the
     * editor loaded, and bumps it on success.
     *
     * <p>The check is in the {@code where} clause, not read-then-write in the
     * service: two editors saving at the same moment would both pass a
     * read-then-compare, and exactly one can satisfy this predicate. An empty
     * result is a conflict, not a missing row, and the caller answers 409.
     *
     * <p>// req: FR-CONT-013
     */
    public Optional<PageVersion> updateDraft(
            UUID versionId, int expectedVersionNo, String title, String description, JsonNode body) {

        return jdbc.sql("""
                        update page_version
                        set title = ?, description = ?, body = ?::jsonb,
                            version_no = version_no + 1, updated_at = now()
                        where id = ? and version_no = ? and status = 'draft'
                        returning id, page_id, locale, status, title, description, body::text as body,
                                  version_no, source_version_id, created_by, created_at, updated_at
                        """)
                .params(title, description, jsonb.write(body), versionId, expectedVersionNo)
                .query(mapper)
                .optional();
    }

    /**
     * Retires the currently published row for a locale, if there is one.
     *
     * <p>Must run before a draft is promoted: the one-published-row index is not
     * deferrable, so a promote-then-archive order would violate it mid
     * transaction even though the end state is legal.
     */
    public int archivePublished(UUID pageId, Locale locale) {
        return jdbc.sql("""
                        update page_version
                        set status = 'archived', updated_at = now()
                        where page_id = ? and locale = ?::locale and status = 'published'
                        """)
                .params(pageId, locale.wire())
                .update();
    }

    /**
     * Promotes a draft to published, recording which {@code en} version a
     * translation was made from.
     *
     * <p>{@code sourceVersionId} is what makes staleness derivable rather than a
     * stored flag: {@code translation_status} compares it against the current
     * published {@code en} version, so it cannot drift out of agreement with
     * reality (ADR-003).
     *
     * <p>// req: FR-CONT-005, FR-I18N-002
     */
    public Optional<PageVersion> promoteDraft(UUID versionId, UUID sourceVersionId) {
        return jdbc.sql("""
                        update page_version
                        set status = 'published', source_version_id = ?, updated_at = now()
                        where id = ? and status = 'draft'
                        returning id, page_id, locale, status, title, description, body::text as body,
                                  version_no, source_version_id, created_by, created_at, updated_at
                        """)
                .params(sourceVersionId, versionId)
                .query(mapper)
                .optional();
    }

    public int deleteDraft(UUID pageId, Locale locale) {
        return jdbc.sql("""
                        delete from page_version
                        where page_id = ? and locale = ?::locale and status = 'draft'
                        """)
                .params(pageId, locale.wire())
                .update();
    }

    // -----------------------------------------------------------------------
    // Translation coverage
    // -----------------------------------------------------------------------

    /**
     * Reads the {@code translation_status} view.
     *
     * <p>Coverage and staleness are different measures and are reported
     * separately: a missing translation is not a stale one, and conflating them
     * was AMB-002.
     *
     * <p>// req: FR-I18N-003, NFR-I18N-01
     */
    public List<TranslationStatus> translationStatus(String sectionSlug) {
        String sql = """
                select page_id, section_slug, page_slug, locale, exists_published, is_stale
                from translation_status
                """
                + (sectionSlug == null ? "" : "where section_slug = ?\n")
                + "order by section_slug, page_slug, locale";

        var spec = jdbc.sql(sql);
        if (sectionSlug != null) {
            spec = spec.param(sectionSlug);
        }
        return spec.query((rs, n) -> new TranslationStatus(
                        rs.getObject("page_id", UUID.class),
                        rs.getString("section_slug"),
                        rs.getString("page_slug"),
                        rs.getString("locale"),
                        rs.getBoolean("exists_published"),
                        rs.getBoolean("is_stale")))
                .list();
    }

    /** One row of the {@code translation_status} view. */
    public record TranslationStatus(
            UUID pageId,
            String sectionSlug,
            String pageSlug,
            String locale,
            boolean existsPublished,
            boolean stale) {}
}
