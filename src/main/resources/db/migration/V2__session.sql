-- Coc Docs Platform -- server-side sessions
--
-- ADR-012 moved authentication across a service boundary: the backend verifies
-- the Google ID token and issues its own session credential, and the frontend
-- never asserts identity. That credential has to be revocable server-side,
-- because FR-AUTH-007 requires sign-out to be a real invalidation rather than
-- the client being asked politely to forget a cookie. A stateless JWT cannot
-- do that without a revocation list, which is this table with extra steps.
--
--   // req: FR-AUTH-004, FR-AUTH-007, NFR-SEC-SESSION, ADR-011, ADR-012

create table app_session (
  -- SHA-256 of the opaque token, never the token itself. A database read gives
  -- an attacker no usable credential, which is the same reason a password table
  -- would store a hash -- except here there is no password to begin with.
  token_hash   bytea       primary key,

  account_id   uuid        not null references account(id) on delete cascade,

  issued_at    timestamptz not null default now(),
  -- TTL is a single system-wide value: 1 day (AMB-006, ADR-011). Stored as an
  -- absolute instant so that shortening the configured TTL later cannot extend
  -- a session already issued.
  expires_at   timestamptz not null,

  -- Set on sign-out. Kept rather than deleted so that the audit trail can show
  -- a session was deliberately ended, not merely expired.
  revoked_at   timestamptz,

  -- Recorded for incident response, not for authorisation: a session is never
  -- rejected because the address changed, since that would sign out anyone on
  -- mobile data. // req: FR-OPS-002
  user_agent   text,
  ip_address   inet,

  constraint session_expires_after_issue check (expires_at > issued_at)
);

-- The only lookup on the hot path: resolve a presented token to a live session.
create index app_session_live_idx
  on app_session (account_id)
  where revoked_at is null;

-- Supports the expiry sweep without scanning live rows.
create index app_session_expiry_idx on app_session (expires_at);
