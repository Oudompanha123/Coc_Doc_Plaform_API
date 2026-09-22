-- Rename the Usage product's index tab from 'Dashboard' to 'Overview'.
-- The page tracks Claude Code token usage per teammate — 'Overview' matches
-- every other product's index label and describes the page more accurately.
--
-- Also guards against databases where the V14 empty-slug row was never
-- inserted (e.g. a DB snapshot taken before the usage product was seeded),
-- so the admin always sees three items for the usage product.
update nav_item
    set label = 'Overview', updated_at = now()
    where product_key = 'usage' and slug = '';

insert into nav_item (product_key, slug, label, sort_order)
select 'usage', '', 'Overview', 1
where not exists (
    select 1 from nav_item where product_key = 'usage' and slug = ''
);
