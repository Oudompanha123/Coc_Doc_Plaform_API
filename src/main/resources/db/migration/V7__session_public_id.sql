-- Coc Docs Platform -- an identifier for a session that is safe to hand out
--
-- WHY
-- The Security screen lists the devices you are signed in on and lets you end
-- one. That needs a name for each session that can travel to a browser and
-- come back in a URL.
--
-- app_session's primary key is token_hash: the SHA-256 of the session token.
-- Using it as that name would publish credential-derived material to the very
-- client the credential authenticates. It is a one-way hash, so it does not
-- hand anyone a working session -- but it hands them the exact value the
-- server compares against, which is the sort of thing that only has to become
-- useful once. An opaque surrogate costs one column.
--
-- Not the primary key: token_hash stays the key, because resolving a presented
-- token is the hot path on every request and it looks the row up by hash.
-- This is a second, unique identity used only by the account-facing API.
--
--   // req: FR-AUTH-004, FR-AUTH-007, NFR-SEC-SESSION, ADR-011

-- WHY `if not exists`, WHEN A MIGRATION SHOULD NORMALLY BE EXACT
-- A migration that runs once against a lineage it owns should fail loudly on
-- an unexpected state, and the others in this project do.
--
-- This one cannot assume it owns its lineage. The shared development database
-- had its schema rebuilt by hand twice in one morning, and on 2026-09-14 this
-- migration's history row was deleted while the column it had added was left
-- in place. The database then claimed the column did not exist while it did,
-- and the strict form failed:
--
--   ERROR: column "id" of relation "app_session" already exists
--
-- The defensive form reaches the same schema from either starting point. It is
-- a concession to how that database is being operated, not a pattern to copy
-- into migrations that do not need it.
alter table app_session add column if not exists id uuid not null default gen_random_uuid();

-- Unique rather than a plain index: it names one session, and a duplicate
-- would make "end this session" ambiguous in the one place that must not be.
create unique index if not exists app_session_public_id_idx on app_session (id);
