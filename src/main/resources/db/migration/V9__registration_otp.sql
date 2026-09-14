-- Coc Docs Platform — email OTP for registration
-- Implements ADR-021 (revision of ADR-014/ADR-017): a registration
-- application is held here, unverified, until its emailed 6-digit code is
-- confirmed. Only then does AccountService.register create the account row
-- — this table never becomes one, it is consumed by that call.
--                            // req: FR-AUTH-002, ADR-014, ADR-017, ADR-021

-- Same "hash only" shape as password_reset_token (V6), for the same reason:
-- a leaked row is not a usable code. password_hash is stored exactly as
-- account.password_hash would be -- already bcrypt-hashed by the web layer
-- before this table ever sees it, never plaintext.
create table registration_challenge (
  id            uuid        primary key default gen_random_uuid(),

  email         text        not null,
  display_name  text        not null,
  password_hash text        not null,

  code_hash     bytea       not null,

  -- Wrong-code guesses against this row. Capped at 5 by
  -- RegistrationOtpService; a fresh code (resend, or resubmitting the form)
  -- always starts this back at 0 -- see ADR-021 Key Choice 3 and its
  -- Consequences for why that is not the brute-force hole it looks like.
  attempts      smallint    not null default 0,

  -- How many times this email has had a code re-issued. Audit-trail colour
  -- only; RegistrationOtpService enforces the 60-second cooldown from
  -- issued_at, not from this count.
  resend_count  smallint    not null default 0,

  issued_at     timestamptz not null default now(),
  -- 10 minutes, fixed and system-wide, same "one behaviour, not a knob"
  -- precedent as app_session.expires_at and password_reset_token's 30
  -- minutes -- shorter here because this code is meant to be typed back in
  -- immediately, not saved for later.
  expires_at    timestamptz not null,

  constraint registration_challenge_expires_after_issue check (expires_at > issued_at)
);

comment on table registration_challenge is
  'A registration application awaiting its emailed 6-digit code (ADR-021, revision of ADR-014/017). Never becomes an account row itself -- AccountService.register creates that, on a confirmed code, exactly as ADR-014/017 already specified. Deleted on successful verification; replaced (not extended) by every resend or resubmission, so a live row is always exactly one still-current secret.';

-- At most one live challenge per email at a time. A second /register
-- submission, or an explicit /register/resend, for an address already
-- mid-verification replaces this row rather than stacking a second one --
-- see RegistrationOtpService.start/resend.
create unique index registration_challenge_email_uq on registration_challenge (lower(email));

-- Supports the scheduled purge (RegistrationOtpMaintenance, mirroring
-- PasswordResetTokenMaintenance) without a full table scan.
create index registration_challenge_expires_idx on registration_challenge (expires_at);
