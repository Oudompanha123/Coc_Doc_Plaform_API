-- Coc Docs Platform -- Sidebar item menu: nav_group + nav_item_override

-- WHY
-- Task "Admin sidebar: kebab item menu (Rename, Move to group, Delete)".
-- PRODUCTS is already fully database-backed (nav_product/nav_item, V14) and
-- supports rename/delete today. The ADMINISTRATION section's items (Users &
-- Roles, Attendance, Claude Usage, Announcements, Legal pages) are not --
-- they are a literal array in frontend/components/layout/nav-config.ts, with
-- no database row to attach a rename/move override to. These two tables
-- give those items a stable identity (their href, already the de facto key
-- `isNavItemActive` matches on) and somewhere to record "an admin changed
-- the label/group" without touching the code-defined defaults.
--
-- `nav_group` seeds ADMINISTRATION and DEVELOPER as built-in rows: an item
-- with no override row stays in its code-defined group (ADMINISTRATION for
-- all five items today); DEVELOPER starts empty and, like any empty group,
-- stays hidden from the sidebar until something is moved into it. Built-in
-- groups can't be renamed or deleted (enforced in NavService, not here --
-- `is_builtin` is read, not acted on, by this migration).
--
-- `nav_item_override` follows notification_preference's sparse-row pattern
-- (V33): only a row that actually overrides something exists; a missing row
-- means "use the code-defined default" for every column. `on delete
-- restrict` on group_id mirrors nav_item -> nav_product's own restrict
-- (V14): a group with items still pointing at it can't be deleted out from
-- under them.
--
-- // req: nav-structure investigation, 2026-09-15; sidebar kebab menu task

create table nav_group (
  id          uuid primary key default gen_random_uuid(),
  name        text not null,
  is_builtin  boolean not null default false,
  sort_order  integer not null,
  created_at  timestamptz not null default now(),
  updated_at  timestamptz not null default now()
);

-- Case-insensitive: the sidebar displays every group name in uppercase, so
-- "Legal" and "LEGAL" would be indistinguishable to an admin picking from
-- the "Move to group" list.
create unique index nav_group_name_ci_unique on nav_group (lower(name));

alter table nav_group
  add constraint nav_group_name_length check (char_length(name) between 1 and 24);

create table nav_item_override (
  item_key     text primary key,
  custom_label text,
  group_id     uuid references nav_group(id) on delete restrict,
  sort_order   integer,
  updated_at   timestamptz not null default now(),
  updated_by   uuid references account(id)
);

alter table nav_item_override
  add constraint nav_item_override_label_length check (custom_label is null or char_length(custom_label) between 1 and 30);

insert into nav_group (name, is_builtin, sort_order) values
  ('ADMINISTRATION', true, 1),
  ('DEVELOPER', true, 2);
