-- Coc Docs Platform -- the Claude Usage roster has no 'invited' state any more
--
--   // req: FR-USAGE-001, ADR-035
--
-- WHY
-- ADR-035 decision 3 always said reporting is the opt-in: ClaudeUsageService
-- .recordUsage creates the member row on a first report, for an email nobody
-- had ever heard of. The invite endpoint was a second way in that produced a
-- row in 'invited' -- a placeholder that did nothing at all until that same
-- first report arrived and promoted it.
--
-- That endpoint is gone (see the commit that removed InviteTeammateModal,
-- POST /api/v1/admin/claude-usage/members and ClaudeUsageService.invite).
-- With it gone, nothing in this system can put a row into 'invited' ever
-- again, and the state became unreachable rather than merely unused.
--
-- Leaving it would have been the quiet option and the worse one. A column
-- default of 'invited' that no code path can produce is a trap for whoever
-- next writes an INSERT without naming the column, and a check constraint
-- listing a value the application cannot create tells a reader the opposite
-- of the truth.
--
-- WHAT THIS DELETES, AND WHY THAT IS SAFE
-- Rows still sitting in 'invited' are placeholders for people who never
-- reported. That is not an assumption: recordUsage promotes 'invited' to
-- 'active' on the first report it receives, so a row that is still 'invited'
-- has, by construction, no claude_usage_daily history to lose. The delete
-- below is bounded by that status for exactly this reason -- it cannot touch
-- an active or a removed row, and so cannot orphan usage.
--
-- Removed rows are deliberately NOT touched. 'removed' means an admin took
-- someone off the dashboard and their history is kept attributable (see the
-- table comment in V22); that is a real state a real person can still be in.

delete from claude_usage_member
where status = 'invited';

-- 'active' is what every surviving creator sets explicitly, so this default
-- now agrees with the only code that inserts.
alter table claude_usage_member
  alter column status set default 'active';

alter table claude_usage_member
  drop constraint claude_usage_member_status_check;

alter table claude_usage_member
  add constraint claude_usage_member_status_check
  check (status in ('active', 'removed'));

comment on column claude_usage_member.status is
  'active: has reported at least once, which is the only way a row is created (ADR-035 decision 3). removed: an admin took them off the dashboard -- ingest still accepts their reports, and nothing promotes a removed row back by itself; see ClaudeUsageService.recordUsage. The third state, invited, was retired in V36 along with the invite endpoint that was the only thing able to produce it.';
