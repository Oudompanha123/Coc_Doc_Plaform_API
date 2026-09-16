-- Coc Docs Platform -- recognising the device a session was issued to
--
-- WHY
-- Settings lists where you are signed in and lets you end any of it, but
-- nothing tells you a session appeared. Somebody signing in as you from their
-- own browser is invisible until you happen to open that page and read it,
-- which is the one moment a person is least likely to be looking.
--
-- The alert needs one thing the table cannot currently answer: whether this
-- account has signed in from this kind of device before. Every row has a
-- user_agent, but matching on it whole makes every Chrome update a new device,
-- and an alert that cries wolf on a browser update is an alert people turn
-- off. So the browser and platform are distilled to a key at sign-in and
-- stored beside the raw string, which is kept exactly as before.
--
--   // req: FR-AUTH-007, NFR-SEC-SESSION, ADR-011, ADR-025, ADR-027
--
-- WHY V16 AND NOT V15, WITH NOTHING AT V15 IN THIS REPOSITORY
-- This was written as V15 and renamed. Somebody else's V15 had already been
-- applied to the shared development database, so Flyway refused to start:
--
--   Migration checksum mismatch for migration version 15
--   -> Applied to database : 1673170774
--   -> Resolved locally    : 57876625
--
-- Two different files claiming one version. The number is not a label, it is
-- the identity Flyway records in its history table, and a version can only
-- ever mean one file.
--
-- Do not close the gap. V15 is a real migration that exists on somebody's
-- machine and in at least one database; renumbering this back would recreate
-- the collision the moment they push. Flyway does not require versions to be
-- contiguous, and application.yml sets ignore-migration-patterns "*:missing"
-- so a database holding a V15 this checkout cannot see still starts.
--
-- The lesson, which this project keeps relearning: take the next version at
-- the moment you write the file, and push it early. A migration that sits
-- unpushed on one machine is a number nobody else knows is taken.

-- Browser and platform only -- "Chrome on Windows", never a version. Computed
-- by DeviceDescription at sign-in, not here: a second implementation in SQL
-- would be a second thing to keep in step, and the two disagreeing is exactly
-- how a security alert becomes noise.
--
-- Nullable, because every row written before this migration has no key and
-- there is no honest way to invent one for them. See new_device below.
alter table app_session add column if not exists device_key text;

-- Whether this session was the first from its device_key. Recorded rather than
-- derived, because the answer is only true at the instant of sign-in: by the
-- time anything reads it, the row itself is a prior session from that device
-- and a derived query would always say no.
alter table app_session
  add column if not exists new_device boolean not null default false;

-- WHY EXISTING ROWS ARE LEFT ALONE
-- Backfilling would mean writing the browser/platform rules a second time, in
-- SQL, and a rollout where the two versions disagree alerts everybody about
-- the machine they are sitting at. Leaving device_key null instead means the
-- first sign-in per account after this deploys finds no known device and is
-- treated as seeding, not as an intrusion: recorded, not announced. One quiet
-- sign-in per account, once, in exchange for no false alarms at all.

-- The sign-in question, asked twice per sign-in: has this account used this
-- device before, and has it used any device this system has a key for.
create index if not exists app_session_device_idx
  on app_session (account_id, device_key)
  where device_key is not null;

comment on column app_session.device_key is
  'Browser and platform of the user agent, e.g. "Chrome on Windows" (DeviceDescription). A recognition heuristic for sign-in alerts, never an identity: two machines running the same browser on the same OS share a key. Null for sessions issued before V16.';

comment on column app_session.new_device is
  'True when no earlier session for this account carried the same device_key. False for a sign-in made before any device_key existed for the account, which is seeding rather than a recognised device.';
