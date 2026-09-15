-- Coc Docs Platform — where a push notification is delivered
-- Implements ADR-025: a super admin learns a registration is waiting without
-- going to look for it.                   // req: FR-AUTH-003, FR-AUTH-008, ADR-025

-- One row per app instance a person has granted notification permission on.
-- One human has several: a work laptop, a home browser, and — once the mobile
-- app exists — a phone. All three are separate tokens for the same account.
create table device_token (
  -- FCM issues one registration token per app instance and it is already
  -- unique, so it is the key. A surrogate id would add a column, an index and
  -- a lookup, and buy nothing.
  token        text        primary key,

  -- on delete cascade, matching app_session and password_reset_token: a
  -- deleted account (ADR-023) takes its devices with it, and a device left
  -- pointing at a removed account would be an address nobody can revoke.
  account_id   uuid        not null references account(id) on delete cascade,

  -- 'web' today; 'android' and 'ios' are expected. Recorded from the start
  -- rather than migrated in later, because backfilling a platform onto tokens
  -- already in the wild means guessing from a user agent.
  platform     text        not null,

  -- So a person can tell their own devices apart when revoking one. Free text
  -- from the client and never used in a decision — display only.
  user_agent   text,

  issued_at    timestamptz not null default now(),

  -- Touched whenever the client re-registers the same token, which the SDK
  -- does on its own schedule. A row that has not been seen for a long time is
  -- a candidate for the purge; it is not evidence of anything on its own.
  last_seen_at timestamptz not null default now(),

  constraint device_token_platform_known check (platform in ('web', 'android', 'ios'))
);

comment on table device_token is
  'Push destinations, one row per app instance (ADR-025). Deleted when FCM reports the token dead, when the person signs out, and by cascade when the account goes. Nothing here is a system of record — the pending queue is.';

-- The send path''s only query: every device belonging to the accounts being
-- notified.
create index device_token_account_idx on device_token (account_id);

-- Supports the scheduled purge of tokens FCM has stopped acknowledging,
-- mirroring SessionMaintenance and PasswordResetTokenMaintenance.
create index device_token_last_seen_idx on device_token (last_seen_at);
