-- Coc Docs Platform -- Store: builds return, and an app spans platforms (ADR-111)
--
-- WHY
-- ADR-110 removed builds; the product owner has since asked for them back in
-- a different shape (ADR-111):
--   - An app is no longer tied to one platform. "Add App" asks for a name
--     and an icon only; each uploaded file is filed under the platform its
--     type belongs to (.apk/.aab Android, .ipa iOS, .exe/.msi/.msix/.appx
--     Windows, .dmg/.pkg macOS), and the app page shows a tab per platform.
--   - "Next version" uploads one file for one version, published as
--     Production, Development, or both at once.
--
-- store_app.platform goes: it described the old one-platform-per-app model
-- and nothing reads it any more. The apps themselves, their app_no, names,
-- logos and visibility are untouched.
--
-- store_build is new rather than a re-creation of store_app_release (V82,
-- dropped by V84): it carries a platform per build. V82-V84 are not edited --
-- any of them may already be applied, and Flyway refuses an applied
-- migration whose content changed or whose file is missing.
--
-- Rules carried over from ADR-108:
--   - At most one live build per (app, platform, version, environment) --
--     a partial unique index, so a removed build's version can be uploaded
--     again. Removing a build stamps removed_at and keeps the row, so the
--     history shows "Removed".
--   - file_path is relative to the store root:
--     {platform}/{version}/{environment}/{app id}/{file name}. A file
--     published to both environments is stored once per environment.
--
-- // req: ADR-111

alter table store_app drop column platform;

create table store_build (
  id           uuid primary key default gen_random_uuid(),
  app_id       uuid not null references store_app(id) on delete cascade,
  platform     text not null check (platform in ('android', 'ios', 'windows', 'macos')),
  version      text not null check (version ~ '^[0-9A-Za-z]([0-9A-Za-z._+-]{0,62}[0-9A-Za-z])?$'),
  environment  text not null check (environment in ('production', 'development')),
  notes        text not null default '' check (char_length(notes) <= 1500),
  file_name    text not null,
  file_path    text not null,
  size_bytes   bigint not null check (size_bytes > 0),
  sha256       text not null check (sha256 ~ '^[0-9a-f]{64}$'),
  created_at   timestamptz not null default now(),
  created_by   uuid references account(id) on delete set null,
  removed_at   timestamptz,
  removed_by   uuid references account(id) on delete set null
);

create unique index store_build_live_key
  on store_build (app_id, platform, version, environment)
  where removed_at is null;

create index store_build_app_idx on store_build (app_id, created_at desc);

comment on table store_build is
  'One uploaded install file of a Store app, for one platform, version and environment (ADR-111). Files live on local disk.';
comment on column store_build.removed_at is
  'When an admin removed this build. Its file is deleted; the row stays so the history shows "Removed". NULL = live.';
