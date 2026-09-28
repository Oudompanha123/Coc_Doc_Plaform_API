-- Coc Docs Platform -- per-model Claude usage, kept rather than discarded (ADR-054)
--
-- WHY
-- The reporter (push-usage.mjs) has always sent a per-model breakdown in
-- byModel -- input, output, cache read and cache write for each model -- but
-- ClaudeUsageService.recordUsage only priced it and then threw it away:
-- claude_usage_daily keeps one flat row per (member, day), so "Usage by
-- Model" (the way Claude Code's own usage page reports) could not be
-- answered from the database at all. This table keeps that breakdown.
--
-- claude_usage_daily is unchanged and stays the source of truth for the
-- day's totals, cost, budget and chart; this is its per-model detail, one row
-- per (member, day, model), replaced wholesale on every ingest of that day --
-- the same "the reporter sends the running total, never a delta" rule
-- (ADR-035 decision 3) that keeps a replay from double-counting.
--
-- cache_creation_1h_tokens is the part of cache_creation_tokens written with
-- the 1-hour TTL (usage.cache_creation.ephemeral_1h_input_tokens in the
-- Claude Code transcript). It prices at 2x input rather than 5-minute's
-- 1.25x, and Claude Code writes almost all of its cache at 1 hour, so
-- without it every cost figure understates.
--
--   // req: FR-USAGE-002, FR-USAGE-005, ADR-036, ADR-054

create table claude_usage_daily_model (
  member_id                uuid          not null references claude_usage_member(id) on delete cascade,
  usage_date               date          not null,
  model                    text          not null,
  input_tokens             bigint        not null default 0,
  output_tokens            bigint        not null default 0,
  cache_read_tokens        bigint        not null default 0,
  cache_creation_tokens    bigint        not null default 0,
  cache_creation_1h_tokens bigint        not null default 0,
  cost_usd                 numeric(14,6) not null default 0,

  constraint claude_usage_daily_model_pkey primary key (member_id, usage_date, model),
  constraint claude_usage_daily_model_tokens_check check (
    input_tokens >= 0 and output_tokens >= 0
    and cache_read_tokens >= 0 and cache_creation_tokens >= 0
    and cache_creation_1h_tokens >= 0
    and cache_creation_1h_tokens <= cache_creation_tokens
  )
);

comment on table claude_usage_daily_model is
  'Per-model detail of claude_usage_daily (ADR-054): one row per (member, day, model), replaced as a set on every ingest of that day so a replay converges instead of double-counting.';

comment on column claude_usage_daily_model.cache_creation_1h_tokens is
  'The part of cache_creation_tokens written with the 1-hour TTL (priced at 2x input, vs 1.25x for 5-minute). Always <= cache_creation_tokens.';

comment on column claude_usage_daily_model.cost_usd is
  'API-equivalent estimate for this model alone, priced server-side at ingest (ClaudeUsagePricing), never trusted from the reporter. Scale 6 so a cheap model''s fraction of a cent survives.';
