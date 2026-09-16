-- Coc Docs Platform — TOTP (Google Authenticator) second factor
-- Implements ADR-028: an opt-in second factor gating /login-password only.
-- Two tables, both mirroring the hashed-challenge shape ADR-021 already
-- established for registration_challenge.
--                            // req: FR-AUTH-002, FR-AUTH-009, ADR-011, ADR-028

-- One row per account that has ever started TOTP setup. confirmed_at is
-- null while a setup attempt is pending its confirmation code -- see
-- TotpService.startSetup/confirmSetup. Deleted entirely on disable
-- (TotpService.disable), not merely unconfirmed again, so re-enrolling
-- always starts from a fresh secret.
--
-- secret is plain text, not hashed or encrypted: unlike account.password_hash,
-- a TOTP secret has to be read back to compute the expected code, so a
-- one-way hash cannot be used, and no application-layer encryption key
-- exists anywhere in this codebase to encrypt it with (ADR-007 is N/A for
-- exactly this reason). It sits in the same trust boundary as the database
-- connection string -- see ADR-028 Key Choice 2 and Consequences.
create table account_totp (
  id              uuid        primary key default gen_random_uuid(),
  account_id      uuid        not null references account(id) on delete cascade,

  secret          text        not null,

  -- Set once TotpService.confirmSetup accepts a real code. Null means "a
  -- setup attempt is in flight, not yet usable to gate sign-in" --
  -- SessionService.signInWithPassword only ever consults confirmed rows.
  confirmed_at    timestamptz,

  -- Anti-replay: the last 30-second step index a real code was accepted
  -- for. A code is valid for +/-1 step (matching Authenticator's own
  -- tolerance), so without this the same captured code could be replayed
  -- within that window. Not consulted on the developer fake-code path
  -- (app.otp.enabled=false) -- that path has no real step to track.
  last_used_step  bigint,

  created_at      timestamptz not null default now(),
  updated_at      timestamptz not null default now()
);

comment on table account_totp is
  'TOTP second factor for password sign-in (ADR-028). At most one row per account, enforced below. confirmed_at null = setup in progress, not yet usable to gate sign-in.';

-- At most one TOTP enrollment per account, whether pending or confirmed --
-- TotpService.startSetup replaces an existing pending row rather than
-- stacking a second one, and refuses outright if a confirmed row already
-- exists (disable first).
create unique index account_totp_account_uq on account_totp (account_id);

-- One row per password sign-in that is waiting on its TOTP code --
-- mirrors registration_challenge/password_reset_token exactly: opaque
-- token, hashed at rest (a leaked row is not a usable challenge), short
-- TTL, single-use (deleted by TotpService.verifyLoginChallenge on success).
create table mfa_login_challenge (
  id            uuid        primary key default gen_random_uuid(),
  account_id    uuid        not null references account(id) on delete cascade,

  token_hash    bytea       not null,

  issued_at     timestamptz not null default now(),
  -- 5 minutes: long enough to switch to an authenticator app and type a
  -- code, short enough that an abandoned attempt is not a standing,
  -- password-already-proven credential sitting in a cookie jar.
  expires_at    timestamptz not null,

  constraint mfa_login_challenge_expires_after_issue check (expires_at > issued_at)
);

comment on table mfa_login_challenge is
  'A password sign-in that has passed its first factor and is waiting on a TOTP code (ADR-028). Deleted on successful verification; purged when expired by MfaLoginChallengeMaintenance, mirroring RegistrationOtpMaintenance/PasswordResetTokenMaintenance.';

create unique index mfa_login_challenge_token_hash_uq on mfa_login_challenge (token_hash);

-- Supports the scheduled purge without a full table scan, same as
-- registration_challenge_expires_idx.
create index mfa_login_challenge_expires_idx on mfa_login_challenge (expires_at);
