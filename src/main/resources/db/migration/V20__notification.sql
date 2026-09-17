-- Coc Docs Platform -- notifications as rows
--
-- WHY
-- The bell derived everything from live state: a new-device sign-in alert was
-- a filter over the session list, so it vanished when that session was revoked
-- or expired. The case the alert exists for -- somebody signed in, did
-- something, and left -- is exactly the case that left nothing behind. Read
-- marks were localStorage, so reading on a laptop left it unread on a phone.
--
-- ADR-025 named this table and deferred it; ADR-027 deferred it again. Four
-- notification sources and three different answers to read state later, this
-- is it. See ADR-034.
--
--   // req: FR-AUTH-008, NFR-SEC-PII, ADR-034

create table notification (
  id          uuid        primary key default gen_random_uuid(),

  -- One row per RECIPIENT per event, not one per event. A registration
  -- notifies every active super_admin and they read it at different times or
  -- not at all, so "read" is a property of the pair. Cascade: a notification
  -- addressed to nobody is not a record of anything.
  account_id  uuid        not null references account(id) on delete cascade,

  -- NotificationType.key() -- "auth.new-device", "registration.pending".
  -- Text and not an enum type: adding a notification should be a constant in
  -- Java and a row here, never a migration. The client routes on it.
  type        text        not null,

  -- What it is about, for the deep link. Null for a notification about
  -- nothing in particular. Deliberately not a foreign key: it points at
  -- whatever the type says it does -- a session, an account, a page -- and no
  -- one column can reference all of them. Deletion is handled by retention,
  -- not by cascade.
  target_id   text,

  -- Whatever the type needs to render and route, and nothing else.
  --
  -- The wording is NOT stored. Titles and bodies are rendered from these facts
  -- at read time, so improving a message applies to everything already sent
  -- rather than only to what comes next (ADR-034 decision 2).
  --
  -- NO PERSONAL DATA BEYOND WHAT THE NOTIFICATION MUST SAY. This table is
  -- readable by its recipient, and NFR-SEC-PII confines identity data to
  -- super_admin. A device string is fine. A report body naming sixty
  -- colleagues is not -- the same line V19 drew for hrms_notification.
  payload     jsonb       not null default '{}'::jsonb,

  created_at  timestamptz not null default now(),

  -- Null until the recipient has seen it. A timestamp rather than a boolean:
  -- it answers "when" for free, costs the same, and reads correctly beside
  -- created_at.
  read_at     timestamptz,

  constraint notification_read_after_created check (read_at is null or read_at >= created_at)
);

-- The only query on the hot path: this person's notifications, newest first.
create index notification_inbox_idx
  on notification (account_id, created_at desc);

-- The badge count, which runs on every poll and every stream event. Partial,
-- so it stays proportional to what is unread rather than to what exists.
create index notification_unread_idx
  on notification (account_id)
  where read_at is null;

-- Supports the retention sweep without scanning rows it will keep.
create index notification_created_idx on notification (created_at);

comment on table notification is
  'One row per recipient per notification event (ADR-034). Rendered from type + payload at read time, never from stored text. Swept at 90 days by NotificationMaintenance -- this is a bell, not an archive; audit_log is the archive.';

comment on column notification.type is
  'NotificationType.key(), e.g. "auth.new-device". Add a type rather than repurpose one: old rows re-render under the meaning the key has today.';

comment on column notification.payload is
  'Facts the type needs to render and route. No personal data beyond what the notification must say (NFR-SEC-PII) -- the recipient can read this row.';
