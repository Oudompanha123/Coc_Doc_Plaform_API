-- The sidebar's product list and each product's sub-item list, made
-- admin-editable instead of hardcoded in frontend/lib/docs/jexmcp-nav.ts.
--
-- Deliberately separate from docs_content (V12): this is nav metadata
-- (which products/sub-items exist, and in what order), not page bodies.
-- Removal is blocked at the application layer rather than cascaded here —
-- a product may only be deleted once it has no nav_item rows, and a
-- nav_item may only be deleted once no docs_content row exists for its
-- (product_key, slug) — so `references` below is `restrict`, not `cascade`,
-- to make an accidental orphaning a constraint violation rather than a
-- silent deletion.
--
-- // req: nav-structure investigation, 2026-09-15
create table nav_product (
    key        text        primary key,
    label      text        not null,
    icon       text        not null,
    sort_order integer     not null,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

create table nav_item (
    id          uuid        primary key default gen_random_uuid(),
    product_key text        not null references nav_product (key) on delete restrict,
    slug        text        not null,
    label       text        not null,
    sort_order  integer     not null,
    created_at  timestamptz not null default now(),
    updated_at  timestamptz not null default now(),
    unique (product_key, slug)
);

-- Seeded from the current hardcoded lists (frontend/lib/docs/jex3-sub-items.ts,
-- frontend/lib/docs/jexmcp-nav.ts) so switching the sidebar and docs routing
-- over to this table is not a regression — the live menu is unchanged on
-- day one, just editable from here on.
insert into nav_product (key, label, icon, sort_order) values
    ('jex3-0', 'Jex3.0', 'layers', 1),
    ('jex2-0', 'Jex2.0', 'folder', 2),
    ('coach', 'Coach', 'person', 3),
    ('team', 'Team', 'pulse', 4),
    ('usage', 'Usage', 'barChart', 5);

insert into nav_item (product_key, slug, label, sort_order) values
    ('jex3-0', 'overview', 'Overview', 1),
    ('jex3-0', 'get-started', 'Get started', 2),
    ('jex3-0', 'how-to-use', 'How to use', 3),
    ('jex3-0', 'flow', 'Flow', 4),
    ('jex3-0', 'structure', 'Structure', 5),
    ('jex3-0', 'updating', 'Updating', 6),
    ('jex3-0', 'releases', 'Releases', 7),
    ('jex3-0', 'faq', 'FAQ', 8),
    ('jex3-0', 'prerequisites', 'Prerequisites', 9),
    ('jex3-0', 'run-configs', 'Run configs', 10),
    ('jex3-0', 'from-scratch', 'From scratch', 11),
    ('jex3-0', 'verify', 'Verify', 12),

    ('jex2-0', '', 'Overview', 1),
    ('jex2-0', 'get-started', 'Get started', 2),
    ('jex2-0', 'how-to-use', 'How to use', 3),
    ('jex2-0', 'structure', 'Structure', 4),
    ('jex2-0', 'faq', 'FAQ', 5),

    ('coach', '', 'Overview', 1),
    ('coach', 'quick-start', 'Quick start', 2),
    ('coach', 'how-to-use', 'How to use', 3),
    ('coach', 'workflow', 'Workflow', 4),
    ('coach', 'structure', 'Structure', 5),
    ('coach', 'skills', 'Skills', 6),
    ('coach', 'agents', 'Agents', 7),
    ('coach', 'templates', 'Templates', 8),
    ('coach', 'security-gates', 'Security & gates', 9),
    ('coach', 'releases', 'Releases', 10),

    ('team', '', 'Overview', 1),
    ('team', 'roadmap', 'Roadmap', 2),
    ('team', 'tools', 'Tools', 3),
    ('team', 'workspace', 'Workspace', 4),
    ('team', 'agents', 'Agents', 5),
    ('team', 'demo', 'Demo', 6),
    ('team', 'metrics', 'Metrics', 7),

    ('usage', '', 'Dashboard', 1),
    ('usage', 'setup-guide', 'Setup guide', 2),
    ('usage', 'configuration', 'Configuration', 3);
