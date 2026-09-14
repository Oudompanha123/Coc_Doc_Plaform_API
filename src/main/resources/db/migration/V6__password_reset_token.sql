-- Coc Docs Platform — self-service password reset
-- Implements ADR-017 (revision of ADR-014): a recovery path for the
-- password sign-in path ADR-014 added, closing the gap that ADR explicitly
-- left open ("no password reset flow exists").
--                                          // req: FR-AUTH-002, ADR-014, ADR-017

-- Same shape as app_session (V2), and for the same reason: only the hash of
-- an opaque, unguessable token is ever stored, so a leaked row is not a
-- usable credential. There is nothing in the token to verify a signature
-- against, because it asserts nothing about itself.
create table password_reset_token (
  token_hash   bytea       primary key,

  account_id   uuid        not null references account(id) on delete cascade,

  issued_at    timestamptz not null default now(),
  -- 30 minutes, a fixed system-wide value with no per-deployment variant —
  -- same "one behaviour, not a knob" precedent as app_session.expires_at
  -- and the OIDC nonce TTL.
  expires_at   timestamptz not null,

  -- Single-use. Set when the token is spent to reset a password; a second
  -- presentation of the same token is refused even though it has not yet
  -- expired. Kept rather than deleted so an incident review can see a
  -- token was used, not merely that it no longer exists.
  used_at      timestamptz,

  constraint password_reset_token_expires_after_issue check (expires_at > issued_at)
);

comment on table password_reset_token is
  'One-time, hashed password-reset tokens (ADR-017). Only token_hash is stored, exactly like app_session — a leaked row is not a usable credential. Eligible only for an account that already holds a password_hash (ADR-017 decision 2); a Google/Keycloak-only account never gets a row here.';

-- The reset endpoint's one lookup: resolve a presented token to the account
-- it was issued for.
create index password_reset_token_account_idx on password_reset_token (account_id);

-- Supports the scheduled purge (PasswordResetTokenMaintenance, mirroring
-- SessionMaintenance) without a full table scan.
create index password_reset_token_expires_idx on password_reset_token (expires_at);
