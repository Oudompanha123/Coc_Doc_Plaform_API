-- The Usage docs Overview's hero button: "Open Claude Usage" -> /claude-usage
-- becomes "Get Started" -> /docs/usage/setup-guide.
--
-- /claude-usage is the admin-only team dashboard, but /docs/usage is read by
-- every signed-in role, so a normal reader's click was refused. The button
-- now opens the existing Setup guide page, which every reader can open.
--
-- The frontend's SEED_CONTENT carries the new values for databases with no
-- saved row. This updates a row an admin already saved from the editor —
-- the overview's slug is stored as 'index' (docs-content-api.ts wireSlug) —
-- but only while it still points at /claude-usage: a button an admin has
-- since retargeted on purpose is left exactly as they set it.
--
-- // req: FR-USAGE-002
update docs_content
    set body = jsonb_set(
                   jsonb_set(body, '{ctaLabel}', to_jsonb('Get Started'::text)),
                   '{ctaHref}', to_jsonb('/docs/usage/setup-guide'::text)),
        updated_at = now()
    where product_key = 'usage'
      and slug = 'index'
      and body ->> 'ctaHref' = '/claude-usage';
