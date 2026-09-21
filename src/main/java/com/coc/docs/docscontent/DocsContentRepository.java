package com.coc.docs.docscontent;

import com.coc.docs.web.Jsonb;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Reads and writes {@code docs_content} rows — one current body per
 * {@code (product_key, slug)}, overwritten on save rather than versioned
 * (see {@code V13__docs_content.sql} for why this table skips
 * draft/publish, unlike {@code page_version}).
 *
 * <p>// req: ADR-012 investigation, 2026-09-15
 */
@Repository
public class DocsContentRepository {

    private final JdbcClient jdbc;
    private final Jsonb jsonb;

    public DocsContentRepository(JdbcClient jdbc, Jsonb jsonb) {
        this.jdbc = jdbc;
        this.jsonb = jsonb;
    }

    public Optional<JsonNode> find(String productKey, String slug) {
        return jdbc.sql("select body::text as body from docs_content where product_key = ? and slug = ?")
                .params(productKey, slug)
                .query((rs, n) -> jsonb.read(rs.getString("body")))
                .optional();
    }

    /**
     * Which of the given candidate slugs already have a saved admin row for
     * this product — the bulk sibling of {@link #find}, used by {@code
     * ProductContentTable} to render every row's status in one round trip
     * instead of one 404-prone {@link #find} per slug (see {@code
     * DocsContentController}'s javadoc for why a lone {@link #find} 404s when
     * nothing has been saved yet — harmless, but noisy in the browser's
     * network tab at N rows per page load).
     *
     * <p>An empty candidate list is answered without a round trip, since
     * {@code slug in ()} is invalid SQL and there is nothing to look up
     * anyway.
     */
    public Set<String> findSavedSlugs(String productKey, List<String> slugs) {
        if (slugs.isEmpty()) {
            return Set.of();
        }
        String placeholders = String.join(", ", Collections.nCopies(slugs.size(), "?"));
        Object[] params = new Object[slugs.size() + 1];
        params[0] = productKey;
        for (int i = 0; i < slugs.size(); i++) {
            params[i + 1] = slugs.get(i);
        }
        return new LinkedHashSet<>(jdbc.sql(
                        "select slug from docs_content where product_key = ? and slug in (" + placeholders + ")")
                .params(params)
                .query(String.class)
                .list());
    }

    /** For the product docs pages' "About this document" panel — a separate
     *  query rather than folded into {@link #find}, so that endpoint's
     *  response shape (the raw content body, read straight back as
     *  `DocContent` on the frontend) never gains a field that would round-trip
     *  into a save. */
    public Optional<Instant> findUpdatedAt(String productKey, String slug) {
        return jdbc.sql("select updated_at from docs_content where product_key = ? and slug = ?")
                .params(productKey, slug)
                .query((rs, n) -> rs.getTimestamp("updated_at").toInstant())
                .optional();
    }

    /**
     * Inserts or overwrites the row for {@code (productKey, slug)}.
     *
     * <p>Last-write-wins, unlike {@code page_version}'s optimistic lock — this
     * content has no concurrent-draft workflow to protect (single current
     * row, no history), so there is nothing an overwrite could silently lose
     * beyond what {@code updated_at}/{@code updated_by} already record.
     */
    /** Used only when the sub-item it belongs to is removed (see
     *  {@code NavService#deleteItem}) — this table has no standalone delete
     *  path of its own, since an admin only ever clears content by
     *  overwriting it via {@link #upsert}. */
    public int delete(String productKey, String slug) {
        return jdbc.sql("delete from docs_content where product_key = ? and slug = ?")
                .params(productKey, slug)
                .update();
    }

    public JsonNode upsert(String productKey, String slug, String kind, JsonNode body, UUID updatedBy) {
        return jdbc.sql("""
                        insert into docs_content (product_key, slug, kind, body, updated_by, updated_at)
                        values (?, ?, ?, ?::jsonb, ?, now())
                        on conflict (product_key, slug)
                        do update set kind = excluded.kind,
                                      body = excluded.body,
                                      updated_by = excluded.updated_by,
                                      updated_at = now()
                        returning body::text as body
                        """)
                .params(productKey, slug, kind, jsonb.write(body), updatedBy)
                .query((rs, n) -> jsonb.read(rs.getString("body")))
                .single();
    }
}
