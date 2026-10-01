-- Coc Docs Platform -- Claude usage activity: requests, sessions, active time, projects (ADR-074)
--
-- WHY
-- claude_usage_daily and claude_usage_daily_model answer "how many tokens, on
-- which model, on which day". The admin User Activity detail also needs "how
-- often", "for how long" and "on what project" -- none of which can be derived
-- from a day's token total. The reporter (claude-team-usage >= this change)
-- now computes them from the same transcripts it already reads and sends them
-- as an optional `activity` object on POST /api/ingest. These tables keep it.
--
-- Definitions (computed reporter-side; ADR-074 has the measurements behind
-- them):
--   request       one human turn (a distinct Claude Code promptId)
--   API call      one deduplicated model response -- the unit tokens are summed over
--   conversation  one Claude Code sessionId
--   session       a block of continuous activity, split after 30 min idle
--   active time   sum over sessions of (last event - first event)
--   project       the git repository's folder NAME -- never a path
--
-- SAME RULES AS claude_usage_daily_model (ADR-058)
-- One set per (member, day), replaced wholesale on every ingest of that day:
-- the reporter sends running totals, never deltas, so a replay converges.
-- claude_usage_daily stays the source of truth for a day's tokens and cost;
-- the per-project token columns here are a split of the same responses and
-- sum to it. An ingest without `activity` (an older reporter) removes that
-- day's activity rather than leaving detail that describes a different upload
-- than the totals beside it.
--
-- NO ROW MEANS "NOT REPORTED", NEVER ZERO
-- A day with tokens but no claude_usage_daily_activity row was reported by a
-- reporter that predates this table. The API surfaces that as null, and the
-- UI as "not reported" -- never as 0 requests / 0 minutes.
--
--   // req: FR-USAGE-001, FR-USAGE-002, NFR-SEC-PII, ADR-074

create table claude_usage_daily_activity (
  member_id           uuid    not null references claude_usage_member(id) on delete cascade,
  usage_date          date    not null,
  request_count       integer not null default 0,
  api_call_count      integer not null default 0,
  conversation_count  integer not null default 0,
  session_count       integer not null default 0,
  active_seconds      integer not null default 0,

  constraint claude_usage_daily_activity_pkey primary key (member_id, usage_date),
  constraint claude_usage_daily_activity_counts_check check (
    request_count >= 0 and api_call_count >= 0 and conversation_count >= 0
    and session_count >= 0 and active_seconds between 0 and 86400
  )
);

create table claude_usage_daily_project (
  member_id              uuid        not null,
  usage_date             date        not null,
  project                text        not null,
  request_count          integer     not null default 0,
  api_call_count         integer     not null default 0,
  conversation_count     integer     not null default 0,
  session_count          integer     not null default 0,
  active_seconds         integer     not null default 0,
  input_tokens           bigint      not null default 0,
  output_tokens          bigint      not null default 0,
  cache_read_tokens      bigint      not null default 0,
  cache_creation_tokens  bigint      not null default 0,
  first_at               timestamptz,
  last_at                timestamptz,

  constraint claude_usage_daily_project_pkey primary key (member_id, usage_date, project),
  constraint claude_usage_daily_project_day_fkey foreign key (member_id, usage_date)
    references claude_usage_daily_activity (member_id, usage_date) on delete cascade,
  constraint claude_usage_daily_project_name_check check (length(project) between 1 and 100),
  constraint claude_usage_daily_project_counts_check check (
    request_count >= 0 and api_call_count >= 0 and conversation_count >= 0
    and session_count >= 0 and active_seconds between 0 and 86400
    and input_tokens >= 0 and output_tokens >= 0
    and cache_read_tokens >= 0 and cache_creation_tokens >= 0
  ),
  constraint claude_usage_daily_project_span_check check (
    first_at is null or last_at is null or last_at >= first_at
  )
);

create table claude_usage_activity_session (
  member_id      uuid        not null,
  usage_date     date        not null,
  started_at     timestamptz not null,
  ended_at       timestamptz not null,
  request_count  integer     not null default 0,
  projects       text[]      not null default '{}',

  constraint claude_usage_activity_session_pkey primary key (member_id, usage_date, started_at),
  constraint claude_usage_activity_session_day_fkey foreign key (member_id, usage_date)
    references claude_usage_daily_activity (member_id, usage_date) on delete cascade,
  constraint claude_usage_activity_session_span_check check (ended_at >= started_at),
  constraint claude_usage_activity_session_requests_check check (request_count >= 0)
);

comment on table claude_usage_daily_activity is
  'Day-level activity (ADR-074): requests, API calls, conversations, idle-split sessions and active seconds for one member and day. Absent = not reported by an older reporter, never zero.';

comment on table claude_usage_daily_project is
  'Per-project split of a day (ADR-074). project is a repository folder name, never a path. Token columns split the same deduplicated responses claude_usage_daily totals.';

comment on table claude_usage_activity_session is
  'One continuous-activity block (split after 30 minutes idle, ADR-074) with the projects active in it.';
