-- Coc Docs Platform — one-time authorization codes for the claude-team-usage CLI's browser login
--
-- WHY (ADR-060)
-- `claude-team-usage scan` signs in through the browser instead of asking for
-- a pasted setup code. A signed-in member approves the CLI on /cli/authorize;
-- the browser then hands the CLI (listening on 127.0.0.1) a short-lived code,
-- and the CLI exchanges that code — plus a PKCE verifier only it ever held —
-- for a personal claude_usage_ingest_token (V35). This table holds those codes
-- between approval and exchange. It is NOT a second credential system: the
-- only thing a row can ever become is an ordinary V35 token.
--
-- DESIGN
-- * code_hash: SHA-256 hex of the raw code. The raw code travels once, in the
--   browser's redirect to 127.0.0.1, and is never stored.
-- * code_challenge: base64url(SHA-256(code_verifier)) — RFC 7636 S256. A code
--   seen in browser history is useless without the verifier, which never
--   leaves the CLI process.
-- * expires_at: two minutes after approval. used_at: set once, atomically, on
--   the first exchange attempt — right verifier or wrong — so a code is
--   single-use in every case.
-- * Rows are deleted opportunistically a day after expiry; nothing here is
--   audit history (approval itself is written to audit_log).
--
--   // req: FR-USAGE-002, NFR-SEC-CHANNEL, ADR-047, ADR-060

create table claude_usage_cli_authorization (
  id             uuid        primary key default gen_random_uuid(),
  account_id     uuid        not null references account(id) on delete cascade,
  code_hash      text        not null unique,
  code_challenge text        not null,
  device         text        not null,
  created_at     timestamptz not null default now(),
  expires_at     timestamptz not null,
  used_at        timestamptz,

  constraint claude_usage_cli_authorization_device_len
    check (char_length(device) between 1 and 60),
  constraint claude_usage_cli_authorization_challenge_len
    check (char_length(code_challenge) = 43)
);

create index claude_usage_cli_authorization_expires_idx
  on claude_usage_cli_authorization (expires_at);

comment on table claude_usage_cli_authorization is
  'One-time, two-minute codes that the claude-team-usage CLI exchanges (with a PKCE verifier) for an ordinary claude_usage_ingest_token (ADR-060). Only the SHA-256 of each code is stored.';
