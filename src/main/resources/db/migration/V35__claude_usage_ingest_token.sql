-- Coc Docs Platform — per-user ingest tokens for the Claude Usage reporter
--
-- WHY (supersedes the shared-secret decision in V22 / ADR-035)
-- ADR-035 chose one shared bearer secret for /api/ingest because it was the
-- simplest thing to hand out. That design has two operational problems that
-- surfaced once the team grew past a couple of people:
--
--   1. There is no way to revoke access for one person without rotating the
--      secret for everyone.
--   2. A leaver's machine keeps reporting indefinitely — the backend has no
--      signal to reject it without coordinator intervention.
--
-- ADR-042 reverses that decision: each admin / super_admin generates their own
-- token from the platform UI, configures it once in their local reporter
-- script, and the backend validates it against their account lifecycle.
-- The shared secret is kept as a fallback during rollout so existing setups
-- do not break the moment this migration runs.
--
-- TOKEN DESIGN
-- * Format: coc_<64 random hex chars>  (256 bits of entropy)
--   The coc_ prefix makes the value recognisable in logs and screenshots so a
--   leak is easy to detect and triage.
-- * The raw bearer value is NEVER stored. Only its SHA-256 hex digest is
--   persisted. The token is displayed once at creation time; it cannot be
--   recovered from this table.
-- * revoked_at is a soft-delete — the row stays for audit history; the backend
--   rejects any token where revoked_at IS NOT NULL.
-- * last_used_at records the most recent accepted ingest so an admin can see
--   that a token is still in active use before choosing to revoke it.
-- * on delete cascade mirrors the "deactivate, never delete" account policy:
--   hard-deleting an account (rare, exceptional) takes its tokens with it.
--   Normal departure: account.deactivated_at is set → the backend's role check
--   rejects the token without touching this table.
--
--   // req: FR-USAGE-001, NFR-SEC-CHANNEL, ADR-042

create table claude_usage_ingest_token (
  id           uuid        primary key default gen_random_uuid(),

  -- The platform account that owns this token. Role is re-checked on every
  -- ingest request (from the live account row), so a role downgrade or
  -- deactivation takes effect immediately without revoking tokens explicitly.
  account_id   uuid        not null references account(id) on delete cascade,

  -- SHA-256 hex digest of the raw coc_... bearer value. 64 lowercase hex chars.
  -- The raw token is not recoverable from this column.
  token_hash   text        not null unique,

  -- Human-readable label the owner gives each machine, e.g. "MacBook Pro".
  -- Shown in the Settings UI so the owner knows which token to revoke.
  label        text        not null,

  created_at   timestamptz not null default now(),

  -- Set to now() on every accepted ingest. Null means the token was generated
  -- but the reporter script has not successfully used it yet.
  last_used_at timestamptz,

  -- Null = active. Set by DELETE /api/me/usage-tokens/{id}. Never physically
  -- deleted so the audit trail is preserved.
  revoked_at   timestamptz,

  constraint claude_usage_ingest_token_label_len
    check (char_length(label) between 1 and 80)
);

-- The hot path: every /api/ingest request hashes the bearer value and looks it
-- up here. The partial index covers only active (non-revoked) tokens, keeping
-- it small and the lookup fast.
create index claude_usage_ingest_token_hash_idx
  on claude_usage_ingest_token (token_hash)
  where revoked_at is null;

-- Used by GET /api/me/usage-tokens to list tokens for the Settings page.
create index claude_usage_ingest_token_account_idx
  on claude_usage_ingest_token (account_id);

comment on table claude_usage_ingest_token is
  'Per-user bearer tokens for the Claude Code usage reporter (ADR-042). Supersedes the single shared secret from ADR-035. The raw token is never stored — only its SHA-256 hex digest. Revocation sets revoked_at rather than deleting the row so audit history is preserved.';

comment on column claude_usage_ingest_token.token_hash is
  'SHA-256 hex digest (64 lowercase chars) of the raw bearer value. The bearer (coc_ prefix + 64 random hex chars) is shown to the user once at creation and cannot be recovered from this column.';

comment on column claude_usage_ingest_token.last_used_at is
  'Timestamp of the most recent accepted /api/ingest request. Null means the token was created but never successfully used. Updated in-place on every ingest — no history kept here.';

comment on column claude_usage_ingest_token.revoked_at is
  'Null means active. Set by DELETE /api/me/usage-tokens/{id}. The backend rejects any request whose token resolves to a row where this is not null. The row is never physically deleted.';
