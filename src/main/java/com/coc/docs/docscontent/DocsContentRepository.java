package com.coc.docs.docscontent;

import com.coc.docs.web.Jsonb;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Reads and writes {@code docs_content} rows — one current body per
 * {@code (product_key, slug)}, overwritten on save rather than versioned
 * (see {@code V12__docs_content.sql} for why this table skips
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
     * Inserts or overwrites the row for {@code (productKey, slug)}.
     *
     * <p>Last-write-wins, unlike {@code page_version}'s optimistic lock — this
     * content has no concurrent-draft workflow to protect (single current
     * row, no history), so there is nothing an overwrite could silently lose
     * beyond what {@code updated_at}/{@code updated_by} already record.
     */
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
