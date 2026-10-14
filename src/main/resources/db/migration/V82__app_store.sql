-- Coc Docs Platform -- App Store: downloadable builds of the team's apps (ADR-108)
--
-- WHY
-- The Store page (/store) lists the team's Android, iOS, Windows and macOS apps and
-- hands out their install files. An admin registers an app once (name, logo,
-- platform, public/private) and then uploads builds of it, each one a
-- version in one environment -- Production or Development.
--
-- Two tables, not one: an app keeps its name and logo across every build,
-- and a build is the thing that has a version, an environment and a file.
-- At most one build per (app, version, environment): re-uploading the same
-- triple is refused (409) rather than silently replacing a file someone may
-- already have downloaded.
--
-- app_no is the app's number in its page URL -- /store-1, /store-2, ... --
-- short and readable where the uuid is not. Assigned by the database, never
-- reused (identity), and never the key anything else joins on: the uuid stays
-- the primary key, and the public logo route keeps using it so a logo cannot
-- be found by counting.
--
-- The files themselves are on the backend's local disk, not in this
-- database and not in object storage -- see ADR-108 for why that departs
-- from CONST-TECH-08. file_path is relative to the configured store root
-- ({platform}/{version}/{environment}/{app id}/{file name}), so moving the
-- root moves every file with it.
--
-- // req: ADR-108

create table store_app (
  id           uuid primary key default gen_random_uuid(),
  app_no       bigint generated always as identity unique,
  name         text not null check (char_length(name) between 1 and 80),
  description  text not null default '' check (char_length(description) <= 2000),
  platform     text not null check (platform in ('android', 'ios', 'windows', 'macos')),
  -- Private apps are visible to admins only; a user can neither list nor
  -- download them.
  is_public    boolean not null default false,
  -- Relative to the store root; NULL until a logo is uploaded.
  logo_path    text,
  logo_type    text check (logo_type in ('image/png', 'image/jpeg', 'image/webp')),
  created_at   timestamptz not null default now(),
  created_by   uuid references account(id) on delete set null,
  updated_at   timestamptz not null default now(),
  updated_by   uuid references account(id) on delete set null,
  check ((logo_path is null) = (logo_type is null))
);

create table store_app_release (
  id           uuid primary key default gen_random_uuid(),
  app_id       uuid not null references store_app(id) on delete cascade,
  version      text not null check (version ~ '^[0-9A-Za-z]([0-9A-Za-z._+-]{0,62}[0-9A-Za-z])?$'),
  environment  text not null check (environment in ('production', 'development')),
  notes        text not null default '' check (char_length(notes) <= 2000),
  file_name    text not null,
  file_path    text not null,
  size_bytes   bigint not null check (size_bytes > 0),
  sha256       text not null check (sha256 ~ '^[0-9a-f]{64}$'),
  created_at   timestamptz not null default now(),
  created_by   uuid references account(id) on delete set null,
  unique (app_id, version, environment)
);

create index store_app_release_app_idx on store_app_release (app_id, created_at desc);

comment on table store_app is 'An app offered on the Store page (ADR-108). Files live on local disk, not here.';
comment on column store_app_release.file_path is 'Relative to the store root: {platform}/{version}/{environment}/{app id}/{file name} (ADR-108).';
