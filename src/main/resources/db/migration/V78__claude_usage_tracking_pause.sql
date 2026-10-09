-- Coc Docs Platform -- Claude usage: when each member's tracking was paused (ADR-105)
--
-- WHY
-- A super_admin can disable a member, and while disabled every report from
-- their reporter is refused (403). But the CLI keeps a refused day in its local
-- ledger as "not sent" and retries it on later scans (up to 30 days back, or
-- any range with --days), and keeps a refused scan log in its queue. Without a
-- record of *when* tracking was paused, the server could not tell those
-- retries from new usage once the member is enabled again, and the disabled
-- period would be backfilled -- the thing disabling exists to prevent.
--
-- One row per disable: disabled_at when a super_admin disabled the member,
-- resumed_at when one enabled them again (null while still disabled). A
-- member disabled and enabled several times has several rows, so an old
-- window cannot be backfilled by a later --days run either.
--
-- How the windows are applied (ClaudeUsageService / ClaudeUsageScanLogService):
--   * usage (a whole reporting day, Asia/Phnom_Penh): refused when its date
--     falls on or between the disable day and the resume day. Both boundary
--     days are refused: a daily total cannot be split at the moment of
--     disabling or enabling, so either day would carry disabled-period usage.
--     A row stored before the disable is kept as it was.
--   * scan logs (a moment): refused when started_at is in [disabled_at, resumed_at).
--
-- Nothing here deletes usage. Rows are kept for the life of the member and
-- removed with it.
--
-- // req: FR-USAGE-001, FR-USAGE-002, ADR-105

create table claude_usage_tracking_pause (
  id           uuid        primary key default gen_random_uuid(),
  member_id    uuid        not null references claude_usage_member(id) on delete cascade,
  disabled_at  timestamptz not null,
  resumed_at   timestamptz,
  constraint claude_usage_tracking_pause_order check (resumed_at is null or resumed_at >= disabled_at)
);

-- At most one open pause per member: disabling twice must not stack windows.
create unique index claude_usage_tracking_pause_open_idx
  on claude_usage_tracking_pause (member_id)
  where resumed_at is null;

create index claude_usage_tracking_pause_member_idx
  on claude_usage_tracking_pause (member_id, disabled_at);

-- Members already disabled before this migration get an open pause, dated by
-- the newest "claude_usage_member.remove" audit entry for them -- the record
-- of when a super_admin disabled them. updated_at is only the fallback (a
-- member removed before that audit action existed): before ADR-105 a removed
-- member's reports still touched display_name, which moves updated_at later
-- than the real disable, so it is the less exact of the two.
insert into claude_usage_tracking_pause (member_id, disabled_at)
select m.id,
       coalesce(
         (select max(a.occurred_at)
            from audit_log a
           where a.action = 'claude_usage_member.remove'
             and a.target_type = 'claude_usage_member'
             and a.target_id = m.id),
         m.updated_at)
from claude_usage_member m
where m.status = 'removed';
