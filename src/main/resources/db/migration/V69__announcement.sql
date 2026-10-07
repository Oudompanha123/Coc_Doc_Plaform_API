-- Coc Docs Platform -- Announcements: admin-authored notices shown on the user site

-- WHY
-- Admins need to tell every signed-in person something -- a maintenance
-- window, a new feature, a policy change -- without a code deploy and without
-- sending mail. The bell (notification, ADR-034) is already where the user
-- site shows things addressed to a person, so a published announcement is
-- delivered there; but the bell is the wrong place to KEEP one:
--
--   * a notification row is a per-recipient copy, so an edit made after
--     publishing would have to rewrite every copy, and a retracted
--     announcement would linger in every inbox;
--   * notification rows are purged after a retention window (a bell is not
--     an archive), and an announcement must outlive that for the admin list.
--
-- So the announcement itself lives here, once, and the bell row only points at
-- it (notification.target_id = announcement.id). The reader always opens the
-- current text from this table; ADR-098 records the decision.
--
-- One row per announcement, not draft/published rows like page_version
-- (ADR-003): an announcement is short-lived, has no history worth browsing,
-- and an edit to a published one is meant to take effect immediately. The
-- status column is a lifecycle, not a version pointer:
--
--   draft      -- visible to admins only
--   published  -- visible to every signed-in user until expires_at, if set
--   archived   -- unpublished; visible to admins only, can be republished
--
-- "Expired" is derived (published and expires_at <= now()), never stored, so
-- nothing has to run at the moment an announcement lapses.
--
-- version_no gives the editor optimistic locking: two admins editing the same
-- announcement cannot silently overwrite each other (same rule as ADR-003).
--
-- // req: ADR-098, ADR-034, ADR-003

create table announcement (
  id uuid primary key default gen_random_uuid(),
  title text not null,
  body text not null,
  category text not null default 'info'
    check (category in ('info', 'update', 'maintenance', 'important')),
  status text not null default 'draft'
    check (status in ('draft', 'published', 'archived')),
  expires_at timestamptz,
  version_no integer not null default 1,
  published_at timestamptz,
  published_by uuid references account(id) on delete set null,
  created_by uuid references account(id) on delete set null,
  updated_by uuid references account(id) on delete set null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  -- Mirrors AnnouncementService's limits so a write that skips the service
  -- still cannot store an empty or unbounded announcement.
  constraint announcement_title_length check (char_length(btrim(title)) between 1 and 150),
  constraint announcement_body_length check (char_length(btrim(body)) between 1 and 5000),
  -- A published row always says when it went out; the reader list orders on it.
  constraint announcement_published_has_time check (status <> 'published' or published_at is not null)
);

-- The reader query: published, newest first.
create index announcement_published_idx on announcement (published_at desc) where status = 'published';

-- The bell row for an announcement is found by (type, target_id) when the
-- announcement is edited, unpublished or deleted.
create index notification_type_target_idx on notification (type, target_id);

comment on table announcement is
  'Admin-authored notices shown to every signed-in user (ADR-098). The bell row (notification) only points here.';
comment on column announcement.expires_at is
  'Null means it stays up until unpublished. Expiry is derived at read time, never stored as a status.';
