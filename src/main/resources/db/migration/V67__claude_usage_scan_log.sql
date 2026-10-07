-- Coc Docs Platform -- Claude usage: one record per reporter scan (ADR-096)
--
-- WHY
-- last_scan_at (V65) answers "when did this person's reporter last check in",
-- and claude_usage_daily answers "what usage was reported". Neither says what
-- happened on a given scan: whether it ran at all, which step failed, or why.
-- Troubleshooting a teammate's reporter meant asking them for the
-- scheduled-scan.log on their own PC. The admin "View Logs" page reads this
-- table instead.
--
-- One row per `claude-team-usage scan` (not --dry-run), sent by the CLI to
-- POST /api/ingest/scan-log at the end of the scan -- including a failed one.
-- A scan that failed because the server was unreachable is queued on the PC
-- and arrives with a later scan; client_scan_id makes that resend idempotent.
--
-- Times: started_at/finished_at are the PC's own clock (they describe the
-- scan); received_at is server time on arrival. status is derived server-side
-- from the steps -- the CLI never sends it -- and "no new usage" is 'success'.
--
-- Holds counts, step names and short redacted messages only: never a token, a
-- transcript line or a prompt. Kept 90 days (pruned on insert by
-- ClaudeUsageScanLogService), unlike audit_log -- it is a troubleshooting aid,
-- not an audit record.
--
-- // req: FR-USAGE-002, ADR-096

create table claude_usage_scan_log (
  id               uuid        primary key default gen_random_uuid(),
  client_scan_id   uuid        not null,
  -- Null for a scan from an email not (yet) on the roster: a first scan that
  -- fails before its first upload is exactly the one worth seeing. Set null,
  -- not cascaded, when a roster row is deleted: the history stays by email.
  member_id        uuid        references claude_usage_member(id) on delete set null,
  email            text        not null,
  trigger          text        not null,
  cli_version      text,
  started_at       timestamptz not null,
  finished_at      timestamptz not null,
  received_at      timestamptz not null default now(),
  status           text        not null,
  upload_status    text        not null,
  transcript_files integer,
  responses        integer,
  duplicates       integer,
  new_tokens       bigint,
  days_uploaded    integer,
  error_step       text,
  error_message    text,
  -- [{key, status, at, detail}] in the fixed step order -- see ClaudeUsageScanLogService.STEPS.
  steps            jsonb       not null default '[]'::jsonb,

  constraint claude_usage_scan_log_client_scan_id_key unique (client_scan_id),
  constraint claude_usage_scan_log_trigger_check check (trigger in ('scheduled', 'manual')),
  constraint claude_usage_scan_log_status_check check (status in ('success', 'warning', 'failed')),
  constraint claude_usage_scan_log_upload_status_check
    check (upload_status in ('uploaded', 'not-needed', 'failed', 'not-attempted')),
  constraint claude_usage_scan_log_times_check check (finished_at >= started_at),
  constraint claude_usage_scan_log_counts_check check (
    coalesce(transcript_files, 0) >= 0 and coalesce(responses, 0) >= 0 and coalesce(duplicates, 0) >= 0
    and coalesce(new_tokens, 0) >= 0 and coalesce(days_uploaded, 0) >= 0)
);

-- The page lists a date range newest first, optionally for one member.
create index claude_usage_scan_log_started_at_idx on claude_usage_scan_log (started_at desc);
create index claude_usage_scan_log_member_started_idx on claude_usage_scan_log (member_id, started_at desc);

comment on table claude_usage_scan_log is
  'One record per claude-team-usage scan: steps, counts, and where it failed (ADR-096). Kept 90 days.';
