-- Coc Docs Platform — per-product minimum role
-- Backs the Overview landing page's "Your Access" panel (extends ADR-029):
-- a real, queryable reason a Viewer (the `user` role) can or can't see a
-- given product, instead of a styled-but-fabricated split.
--
-- Column, not a join table: visibility is a property of the product itself
-- (same shape as icon/label), and there is no per-account override to
-- justify a separate table yet.
--                                                          // req: ADR-029

alter table nav_product
  add column min_role app_role not null default 'user';

comment on column nav_product.min_role is
  'The lowest role that may view this product''s docs (Overview''s "Your Access" panel, and eventually the reader gate itself). Defaults to ''user'' — open to every signed-in role, including a Viewer — so creating a product never silently hides it.';

-- Seeds the split the "Your Access" mockup shows: Team and Usage need
-- admin+, every other existing product stays open at the default.
update nav_product set min_role = 'admin' where key in ('team', 'usage');
