-- Coc Docs Platform -- Claude Code usage tracking, for real (ADR-035)
--
-- WHY
-- The Claude Usage dashboard (admin + super_admin, under Users & Roles)
-- shipped against a static mock array. FR-USAGE-001 called real persistence
-- for this "Won't" for this release (AMB-004) -- the PM/developer pulled it
-- forward in-session; see REQUIREMENTS-SPEC.md v1.6 §7.6 and ADR-035 for the
-- full decision record, including why this is two tables, why ingestion is
-- one shared bearer secret rather than a per-teammate token, and why
-- "Remove" must never delete a row here.
--
--   // req: FR-USAGE-001, FR-USAGE-002, FR-USAGE-003, NFR-SEC-PII,
--   //      NFR-SEC-CHANNEL, ADR-035
--
-- TWO TABLES, NOT ONE
-- claude_usage_member is the opt-in tracking roster: who is being watched,
-- what dashboard-scoped role/budget an admin gave them, whether they are
-- active/invited/removed. It is NOT the account table -- a tracked person
-- need not be able to sign in to this platform at all (a contractor with no
-- login can still be tracked). claude_usage_daily is the raw ingested
-- numbers, one row per (member, day). Splitting them is what lets removing a
-- member stay a display decision: nothing here ever deletes a
-- claude_usage_daily row because an admin clicked Remove, so a former
-- contractor's history stays intact and attributable.

create table claude_usage_member (
  id                     uuid        primary key default gen_random_uuid(),

  -- Lower-cased by the application before every insert/lookup (mirrors
  -- account.email) -- the unique constraint below is only reliable if every
  -- writer normalises first, and there is exactly one writer: ClaudeUsageService.
  email                  text        not null,

  display_name           text        not null,

  -- Dashboard-scoped, deliberately not AppRole. "admin" here means "can also
  -- manage this roster," which has nothing to do with being able to publish
  -- content or grant AppRole itself.
  team_role              text        not null default 'member',

  -- invited: added but has never reported. active: has reported at least
  -- once. removed: an admin took them off the dashboard -- ingest still
  -- accepts their reports (their machine does not know or care that they
  -- were removed), but nothing here promotes a removed row back to active by
  -- itself; see ClaudeUsageService.recordUsage.
  status                 text        not null default 'invited',

  -- A weekly-equivalent soft-budget override, in tokens. Null means "use the
  -- team default" (ClaudeUsageService.DEFAULT_SOFT_BUDGET_TOKENS). Scaled by
  -- the selected period's length when the dashboard renders it, so a Day/
  -- Week/Month view always compares against the same meaning of "budget"
  -- (ADR-035 decision 5).
  budget_override_tokens bigint,

  -- Who invited them, for the audit trail's own completeness -- not a
  -- privilege check. Null when the row was created by their own first
  -- ingest report rather than an admin's invite (ADR-035 decision 3).
  invited_by             uuid        references account(id),

  created_at             timestamptz not null default now(),
  updated_at             timestamptz not null default now(),

  constraint claude_usage_member_email_key unique (email),
  constraint claude_usage_member_team_role_check check (team_role in ('admin', 'member')),
  constraint claude_usage_member_status_check check (status in ('active', 'invited', 'removed')),
  constraint claude_usage_member_budget_check check (budget_override_tokens is null or budget_override_tokens > 0)
);

comment on table claude_usage_member is
  'The opt-in Claude Code usage tracking roster (ADR-035). Not the account table -- a tracked person need not hold a Coc Docs Platform login. Removing someone sets status to removed rather than deleting the row, so claude_usage_daily history stays intact and attributable.';

comment on column claude_usage_member.email is
  'Lower-cased at the application layer before insert -- the unique constraint is only reliable if every writer normalises first.';

comment on column claude_usage_member.status is
  'invited: added but has never reported. active: has reported at least once. removed: an admin removed them from the dashboard -- ingest still accepts their reports, but nothing promotes a removed row back to active on its own. See ClaudeUsageService.';

create index claude_usage_member_status_idx on claude_usage_member (status);

create table claude_usage_daily (
  id                    uuid          primary key default gen_random_uuid(),
  member_id             uuid          not null references claude_usage_member(id) on delete cascade,
  usage_date            date          not null,
  input_tokens          bigint        not null default 0,
  output_tokens         bigint        not null default 0,
  cache_read_tokens     bigint        not null default 0,
  cache_creation_tokens bigint        not null default 0,

  -- API-equivalent estimate at a single blended rate (ClaudeUsageService.
  -- COST_PER_1K_TOKENS), never a real invoice -- same caveat the dashboard UI
  -- already states. Computed server-side at ingest time, never trusted from
  -- the request body, so a rate change takes effect without redeploying
  -- every reporter script.
  cost_usd              numeric(12,4) not null default 0,

  -- Comma-joined display names the reporter said it used that day, e.g.
  -- "Haiku 4.5, Sonnet 5". Free text for display only, never parsed back out
  -- for pricing.
  models                text          not null default '',

  reported_at           timestamptz   not null default now(),

  constraint claude_usage_daily_member_date_key unique (member_id, usage_date),
  constraint claude_usage_daily_tokens_check check (
    input_tokens >= 0 and output_tokens >= 0
    and cache_read_tokens >= 0 and cache_creation_tokens >= 0
  )
);

comment on table claude_usage_daily is
  'One row per (member, day), upserted by POST /api/ingest (ADR-035). The reporter sends the whole day''s running total every tick, never a delta, so a replay or a second run the same day converges instead of double-counting.';

comment on column claude_usage_daily.cost_usd is
  'API-equivalent estimate at a single blended rate, never a real invoice. Computed server-side at ingest time so a rate change is never trusted from the client.';

create index claude_usage_daily_date_idx on claude_usage_daily (usage_date desc);
