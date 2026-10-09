-- Lets a product's URL slug (nav_product.key) follow its name on rename,
-- with permanent redirects from every slug it ever had.
--
-- nav_item.product_key's foreign key was `on delete restrict` with no `on
-- update cascade` (V14) -- V54 worked around that once by hand (insert a new
-- row, repoint nav_item/docs_content, delete the old row) specifically
-- because a plain `update nav_product set key = ...` could not be used. This
-- migration adds the cascade so a rename can instead be one direct UPDATE.
--
-- nav_product_slug_history carries the SAME on-update-cascade back to
-- nav_product.key. That is what makes a rename chain self-resolving without
-- ever walking it: renaming 'a' to 'b' inserts (old_slug='a',
-- product_key='a'), then updating nav_product's key to 'b' cascades into
-- that very row, leaving (old_slug='a', product_key='b'). Renaming 'b' to
-- 'c' later cascades the SAME way into every row still pointing at 'b' --
-- the item's own new history row AND the older 'a' row both land on
-- product_key='c' in the same statement. A lookup by old_slug therefore
-- always returns the product's current key directly, however many times it
-- has been renamed since.
--
-- old_slug is the primary key, so a slug already retired here can never be
-- reused by a different product (the hijack-prevention rule) -- the
-- application must additionally check this table before handing out a new
-- slug, since the database only refuses a second CLAIM of the same old_slug,
-- not a fresh product choosing a key that already retired here.
--
-- `on delete cascade` on both tables' product_key/product reference removes
-- a deleted product's history with it -- there is nothing left to redirect
-- to once the product itself is gone.
--
-- // req: product rename investigation, 2026-10-09

alter table nav_item drop constraint nav_item_product_key_fkey;
alter table nav_item
    add constraint nav_item_product_key_fkey
    foreign key (product_key) references nav_product (key)
    on update cascade on delete restrict;

create table nav_product_slug_history (
    old_slug    text primary key,
    product_key text not null references nav_product (key) on update cascade on delete cascade,
    created_at  timestamptz not null default now()
);

create index nav_product_slug_history_product_key_idx on nav_product_slug_history (product_key);
