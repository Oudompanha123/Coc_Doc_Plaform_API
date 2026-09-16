-- Structured hero/steps/sections content for the product docs pages
-- (frontend/lib/docs/content-types.ts), keyed by product + sub-item slug.
--
-- Deliberately separate from page/page_version: that table stores the
-- ADR-010 ten-block reader-CMS vocabulary, and content-types.ts's hero
-- shape (eyebrow/tagline/CTA fields) has no block equivalent today. This
-- content is also small and closed enough that draft/publish/versioning
-- isn't needed for it — one current row per (product, slug), overwritten
-- on save. A row missing here is not "deleted content": the frontend's
-- SEED_CONTENT stays the fallback when no row exists yet, so nothing a
-- product ships depends on this table being pre-populated.
--
-- // req: ADR-012 (backend stack), investigated 2026-09-15 for the Content
-- editor's localStorage -> real backend task
create table docs_content (
    product_key text        not null,
    slug        text        not null,
    kind        text        not null,
    body        jsonb       not null,
    updated_by  uuid        references account (id),
    updated_at  timestamptz not null default now(),
    primary key (product_key, slug)
);
