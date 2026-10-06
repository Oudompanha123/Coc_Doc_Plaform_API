-- Coc Docs Platform -- Claude usage: when a member's reporter last scanned (ADR-095)
--
-- WHY
-- The dashboard's "Last reported" is the newest claude_usage_daily.reported_at:
-- it moves only when usage is uploaded. The claude-team-usage CLI uploads a day
-- only when it has grown since the last upload (its local ledger, isDue), so a
-- scan that finds nothing new uploads nothing -- correctly -- and "Last
-- reported" stays at the earlier upload. That made a healthy 16:00 scan look as
-- if the machine had stopped reporting at 14:50.
--
-- last_scan_at records the scan itself, separately:
--   - POST /api/ingest           (usage changed)   sets it along with the usage rows;
--   - POST /api/ingest/scan      (nothing changed) sets only it -- no usage row is
--                                written, so nothing is duplicated.
-- Server time on arrival, never a client-supplied clock. One value per member:
-- the most recent scan from any of their machines.
--
-- NULL means "no scan recorded yet" (a member who last reported with an older
-- reporter, or never), not "never scanned".
--
-- // req: FR-USAGE-002, ADR-095

alter table claude_usage_member add column last_scan_at timestamptz;

comment on column claude_usage_member.last_scan_at is
  'When this member''s reporter last completed a scan (any machine), whether or not it uploaded usage. Server time. NULL = none recorded yet (ADR-095).';
