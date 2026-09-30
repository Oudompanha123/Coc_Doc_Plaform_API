-- The AX Renewal docs product's five pages: Overview (the product's own
-- index, empty slug — same convention as coach/jex2-0 in V14), Quick Start,
-- How to Use, Key Features, Workflow. Their content is the frontend's
-- SEED_CONTENT fallback (frontend/lib/docs/content-store.ts), so no
-- docs_content rows are written here — an admin's first save from the
-- dashboard creates those, same as every other product.
--
-- The product itself was created from the dashboard, not a migration, so
-- it only exists on databases where someone did that (and V54 moved it off
-- the reserved 'ax-renewal' key). Insert it where it is missing so every
-- environment gets the same pages; where it already exists its label, icon
-- and position are left exactly as the admin set them. Items that already
-- exist for a slug are likewise left alone.
--
-- Separate from the AX Renewal workspace at /ax-renewal: this is reader
-- documentation about that workspace, served at /docs/ax-renewal-docs/*.
--
-- // req: nav-structure investigation, 2026-09-15 (AX Renewal product docs)
-- `on conflict` rather than a `where not exists` filter: max() is an
-- aggregate, so the select returns one row even when the filter matches
-- nothing, and the insert would fire regardless.
insert into nav_product (key, label, icon, sort_order)
select 'ax-renewal-docs', 'AX Renewal', 'layers', coalesce(max(sort_order), 0) + 1
from nav_product
on conflict (key) do nothing;

insert into nav_item (product_key, slug, label, sort_order) values
    ('ax-renewal-docs', '', 'Overview', 1),
    ('ax-renewal-docs', 'quick-start', 'Quick Start', 2),
    ('ax-renewal-docs', 'how-to-use', 'How to Use', 3),
    ('ax-renewal-docs', 'key-features', 'Key Features', 4),
    ('ax-renewal-docs', 'workflow', 'Workflow', 5)
on conflict (product_key, slug) do nothing;
