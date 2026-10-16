-- Coc Docs Platform -- Store: an app's banner picture, and the featured app (ADR-117)
--
-- WHY
-- The Store page opens with a banner featuring one app (App Store style). By
-- default it is the most recently uploaded or updated app; the product owner
-- asked that an admin can also change it:
--   - upload a wide banner picture for an app, shown behind its name, and
--   - choose which app the banner features, instead of the latest one.
--
-- banner_path / banner_type mirror logo_path / logo_type (V82): a key
-- relative to the store root (banners/{app id}.{ext}) and its image type.
-- Raster only, like the logo -- the banner route is public, and SVG can carry
-- script.
--
-- store_settings is a single row (id is always true) rather than a flag on
-- store_app, so "which app is featured" has exactly one answer by
-- construction. featured_app_id is NULL for "the latest automatically", and
-- falls back to NULL when the featured app is deleted.
--
-- // req: ADR-117

alter table store_app
  add column banner_path text,
  add column banner_type text check (banner_type in ('image/png', 'image/jpeg', 'image/webp'));

create table store_settings (
  id              boolean primary key default true check (id),
  featured_app_id uuid references store_app(id) on delete set null,
  updated_at      timestamptz not null default now(),
  updated_by      uuid references account(id) on delete set null
);

insert into store_settings default values;
