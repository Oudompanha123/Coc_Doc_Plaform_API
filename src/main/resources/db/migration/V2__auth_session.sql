-- Coc Docs Platform — backend-issued sessions
-- Implements docs/design/adr/ADR-011-security-model.md §2 (session management).
--
-- Sessions are looked up by the SHA-256 hash of the opaque cookie value, never
-- by the raw value: a stolen database row cannot be replayed as a cookie, and
-- the application code that inserts/reads/deletes this table never has to
-- reason about the raw token's lifetime separately from the row's.
--
-- Sign-out (FR-AUTH-007) deletes the row outright — invalidation is a real
-- server-side revocation, not a client-side cookie clear (ADR-011 §2), which
-- is what makes an old cookie stop working immediately (TC-004-08).

create table app_session (
  -- sha256(raw cookie value), hex-encoded. Primary key doubles as the lookup
  -- index; no separate id column needed for an append/delete-only table.
  token_hash   text        primary key,
  account_id   uuid        not null references account(id) on delete cascade,
  created_at   timestamptz not null default now(),
  -- TTL is a single, defined value system-wide (NFR-SEC-SESSION) — computed
  -- by the application from app.session-ttl (P1D) at issuance time, not
  -- recomputed per-row here.
  expires_at   timestamptz not null,
  last_seen_at timestamptz not null default now()
);

-- Sign-out and expiry sweeps both delete by account; validation reads by
-- token_hash (the primary key) and additionally filters on expiry.
create index app_session_account_idx on app_session (account_id);
create index app_session_expires_idx on app_session (expires_at);
