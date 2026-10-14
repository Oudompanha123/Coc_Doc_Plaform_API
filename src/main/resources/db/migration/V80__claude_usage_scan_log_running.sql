-- Coc Docs Platform -- Claude usage: a scan log row exists while the scan runs (ADR-107)
--
-- WHY
-- V67's row arrives once, at the end of a scan. Until then the Scan Logs page
-- has nothing to show, and a scan that never finished -- the PC slept, the
-- process was killed -- leaves no trace at all. Nothing could honestly say
-- "In progress".
--
-- From claude-team-usage 0.6.0 the CLI also sends POST /api/ingest/scan-log/start
-- when a scan begins. That inserts the row early with status 'running' and no
-- finished_at; the end-of-scan record (same client_scan_id) then completes the
-- same row. A start that arrives after its result -- they are sent
-- independently -- changes nothing, and a completed row is never reopened.
--
-- 'running' is only what was stored. A row still running long after the
-- server heard of it is reported as 'incomplete' at read time
-- (ClaudeUsageScanLogService.RESULT_TIMEOUT), not rewritten: a result queued on
-- the PC can still arrive and complete it.
--
-- Older CLIs never send a start, so their rows are unchanged: inserted
-- finished, exactly as before.
--
-- // req: FR-USAGE-002, ADR-107

alter table claude_usage_scan_log alter column finished_at drop not null;

alter table claude_usage_scan_log drop constraint claude_usage_scan_log_status_check;
alter table claude_usage_scan_log add constraint claude_usage_scan_log_status_check
  check (status in ('running', 'success', 'warning', 'failed'));

-- A finished scan has a finish time and a running one does not -- never one without the other.
alter table claude_usage_scan_log add constraint claude_usage_scan_log_running_check
  check ((status = 'running') = (finished_at is null));

-- A member's own Scan Logs read (GET /api/v1/claude-usage/me/scan-logs) is by
-- email, since a scan from an email not yet on the roster has no member_id.
create index claude_usage_scan_log_email_started_idx on claude_usage_scan_log (email, started_at desc);

comment on column claude_usage_scan_log.finished_at is
  'The PC''s clock when the scan ended. NULL while status = ''running'' (ADR-107).';
