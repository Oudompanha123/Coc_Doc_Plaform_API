package com.coc.docs.page;

import com.coc.docs.account.AppRole;
import com.coc.docs.content.Locale;
import com.coc.docs.content.PageRefs;
import com.coc.docs.web.Jsonb;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Reads and writes {@code page} rows, plus the slug history that keeps inbound
 * links working across a rename.
 *
 * <p>// req: FR-NAV-004, FR-READ-003, FR-CONT-007, CONST-OPS-02
 */
@Repository
public class PageRepository {

    /** {@code order} is a reserved word, so it stays quoted everywhere it appears. */
    private static final String COLUMNS =
            "id, section_id, slug, \"order\", badge, min_role, deleted_at, created_at";

    private static final RowMapper<Page> MAPPER = PageRepository::map;

    private final JdbcClient jdbc;
    private final Jsonb jsonb;

    public PageRepository(JdbcClient jdbc, Jsonb jsonb) {
        this.jdbc = jdbc;
        this.jsonb = jsonb;
    }

    private static Page map(ResultSet rs, int rowNum) throws SQLException {
        OffsetDateTime deleted = rs.getObject("deleted_at", OffsetDateTime.class);
        return new Page(
                rs.getObject("id", UUID.class),
                rs.getObject("section_id", UUID.class),
                rs.getString("slug"),
                rs.getInt("order"),
                rs.getString("badge"),
                requireRole(rs.getString("min_role")),
                deleted == null ? null : deleted.toInstant(),
                rs.getObject("created_at", OffsetDateTime.class).toInstant());
    }

    /**
     * Fails loudly rather than defaulting. A {@code min_role} the application
     * cannot parse means the database and {@link AppRole} have drifted apart, and
     * quietly substituting a role would either open a page up or lock it away
     * without anyone being told which.
     */
    private static AppRole requireRole(String raw) throws SQLException {
        Optional<AppRole> role = AppRole.parse(raw);
        if (role.isEmpty()) {
            throw new SQLException("page.min_role holds a value outside the app_role enum: " + raw);
        }
        return role.get();
    }

    // -----------------------------------------------------------------------
    // Reads
    // -----------------------------------------------------------------------

    public Optional<Page> findById(UUID id) {
        return jdbc.sql("select " + COLUMNS + " from page where id = ? and deleted_at is null")
                .param(id)
                .query(MAPPER)
                .optional();
    }

    public Optional<Page> findBySectionAndSlug(UUID sectionId, String slug) {
        return jdbc.sql("select " + COLUMNS
                        + " from page where section_id = ? and slug = ? and deleted_at is null")
                .params(sectionId, slug)
                .query(MAPPER)
                .optional();
    }

    public List<Page> findBySection(UUID sectionId) {
        return jdbc.sql("select " + COLUMNS + """
                         from page
                         where section_id = ? and deleted_at is null
                         order by "order", slug
                        """)
                .param(sectionId)
                .query(MAPPER)
                .list();
    }

    /**
     * Navigation entries for one section and locale: the page plus the title of
     * its published version.
     *
     * <p>An inner join, so a page with no published version in this locale does
     * not appear in navigation. That is deliberate — a nav link to a page that
     * has nothing published in the reader's language would render an empty page,
     * which is worse than the link being absent. Coverage gaps are reported
     * through {@code translation_status}, not through broken links.
     *
     * <p>// req: FR-NAV-001, NFR-I18N-01
     */
    public List<NavEntry> findNav(UUID sectionId, Locale locale) {
        return jdbc.sql("""
                        select p.id, p.slug, p."order", p.badge, p.min_role, pv.title
                        from page p
                        join page_version pv
                          on pv.page_id = p.id
                         and pv.locale = ?::locale
                         and pv.status = 'published'
                        where p.section_id = ? and p.deleted_at is null
                        order by p."order", p.slug
                        """)
                .params(locale.wire(), sectionId)
                .query((rs, n) -> new NavEntry(
                        rs.getObject("id", UUID.class),
                        rs.getString("slug"),
                        rs.getInt("order"),
                        rs.getString("badge"),
                        AppRole.parse(rs.getString("min_role")).orElse(AppRole.SUPER_ADMIN),
                        rs.getString("title")))
                .list();
    }

    /**
     * Resolves a slug that no longer exists to the page that used to own it.
     *
     * <p>// req: FR-READ-003, CONST-OPS-02
     */
    public Optional<UUID> findByHistoricalSlug(UUID sectionId, String oldSlug) {
        return jdbc.sql("""
                        select page_id from page_slug_history
                        where section_id = ? and old_slug = ?
                        """)
                .params(sectionId, oldSlug)
                .query((rs, n) -> rs.getObject("page_id", UUID.class))
                .optional();
    }

    /**
     * Pages whose stored content links to {@code target}.
     *
     * <p>Two stages: a {@code jsonb::text} substring match narrows the candidate
     * rows in the database, then {@link PageRefs} confirms each one by walking the
     * block tree. The prefilter is not exact on its own — the id could appear in
     * a code block as plain text — and the tree walk is exact but would mean
     * reading every body if used alone. At the 200-page ceiling in NFR-SCALE-01
     * this is comfortably fast, and it stays correct if that ceiling is wrong.
     *
     * <p>// req: FR-CONT-007
     */
    public List<Page> findReferencing(UUID target) {
        record Candidate(UUID pageId, String body) {}

        List<Candidate> candidates = jdbc.sql("""
                        select distinct pv.page_id, pv.body::text as body
                        from page_version pv
                        join page p on p.id = pv.page_id
                        where pv.status in ('draft', 'published')
                          and p.deleted_at is null
                          and pv.page_id <> ?
                          and pv.body::text like ?
                        """)
                .params(target, "%" + target + "%")
                .query((rs, n) -> new Candidate(
                        rs.getObject("page_id", UUID.class), rs.getString("body")))
                .list();

        List<UUID> confirmed = new ArrayList<>();
        for (Candidate candidate : candidates) {
            if (PageRefs.collect(jsonb.read(candidate.body())).contains(target)
                    && !confirmed.contains(candidate.pageId())) {
                confirmed.add(candidate.pageId());
            }
        }
        if (confirmed.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("select " + COLUMNS + " from page where id = any (?)")
                .param(confirmed.toArray(UUID[]::new))
                .query(MAPPER)
                .list();
    }

    // -----------------------------------------------------------------------
    // Writes
    // -----------------------------------------------------------------------

    public Page insert(UUID sectionId, String slug, int order, String badge, AppRole minRole) {
        return jdbc.sql("""
                        insert into page (section_id, slug, "order", badge, min_role)
                        values (?, ?, ?, ?, ?::app_role)
                        returning id, section_id, slug, "order", badge, min_role, deleted_at, created_at
                        """)
                .params(sectionId, slug, order, badge, minRole.wire())
                .query(MAPPER)
                .single();
    }

    /**
     * Renames a page and records the old slug so inbound links keep resolving.
     *
     * <p>Both statements are one unit of work; a rename that recorded no history
     * would break every existing link to the page, so the caller is expected to
     * hold a transaction.
     *
     * <p>// req: FR-CONT-006, FR-READ-003
     */
    public void rename(Page page, String newSlug) {
        jdbc.sql("""
                        insert into page_slug_history (page_id, section_id, old_slug)
                        values (?, ?, ?)
                        on conflict (section_id, old_slug) do update set page_id = excluded.page_id,
                                                                         changed_at = now()
                        """)
                .params(page.id(), page.sectionId(), page.slug())
                .update();

        jdbc.sql("update page set slug = ? where id = ?").params(newSlug, page.id()).update();
    }

    /** Soft delete. The row stays so that version history and audit references hold. */
    public int softDelete(UUID id) {
        return jdbc.sql("update page set deleted_at = now() where id = ? and deleted_at is null")
                .param(id)
                .update();
    }

    public int reorder(UUID id, int order) {
        return jdbc.sql("update page set \"order\" = ? where id = ?").params(order, id).update();
    }

    /** A navigation entry: page identity plus the published title in one locale. */
    public record NavEntry(
            UUID id, String slug, int order, String badge, AppRole minRole, String title) {}
}
