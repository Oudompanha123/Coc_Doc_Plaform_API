-- Move a dashboard-created product off the key 'ax-renewal' onto
-- 'ax-renewal-docs'. Its sidebar link is `/<key>`, and app/ax-renewal (the
-- AX Renewal developer workspace) is a static route, which Next.js always
-- prefers over app/(shell)/[productKey] — so the product's own docs page
-- could never be opened. NavService now reserves 'ax-renewal' so this
-- cannot be created again.
--
-- nav_item's foreign key is `on delete restrict` with no `on update
-- cascade`, so the key cannot be updated in place: insert the new product
-- row, repoint nav_item and docs_content (which holds the key without a
-- foreign key) at it, then delete the old row. Every statement is keyed on
-- 'ax-renewal', so on a database where no such product was ever created
-- this migration changes nothing.
--
-- // req: nav-structure investigation, 2026-09-15 (reserved product keys)
insert into nav_product (key, label, icon, sort_order, min_role, created_at, updated_at)
select 'ax-renewal-docs', label, icon, sort_order, min_role, created_at, now()
from nav_product
where key = 'ax-renewal';

update nav_item
    set product_key = 'ax-renewal-docs', updated_at = now()
    where product_key = 'ax-renewal';

update docs_content
    set product_key = 'ax-renewal-docs'
    where product_key = 'ax-renewal';

delete from nav_product where key = 'ax-renewal';
