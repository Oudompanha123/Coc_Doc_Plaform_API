-- Coc Docs Platform -- outgoing webhook integrations (ADR-037)
--
-- WHY
-- The Overview dashboard's "Connect Integration" quick action shipped as a
-- static mock card with nothing behind it -- no integrations/webhooks/API-key
-- concept existed anywhere in this codebase. FR-INTEG-001..006
-- (REQUIREMENTS-SPEC.md v1.7 S2.9) asks for a real, super_admin-managed
-- outgoing-webhook feature instead. See ADR-037 for the full design record,
-- including why a generic webhook (not a named OAuth SaaS integration) and
-- why this is the codebase's first application-layer encryption-at-rest
-- primitive.
--
--   // req: FR-INTEG-001, FR-INTEG-002, FR-INTEG-003, FR-INTEG-004,
--   //      FR-INTEG-005, FR-INTEG-006, ADR-037
--
-- TWO TABLES, NOT ONE
-- integration is the connected-integration roster: one row per webhook an
-- admin has connected, its target, its subscribed events, and its encrypted
-- signing secret. integration_delivery is the append-only attempt log --
-- every real delivery and every "Test" click -- the same "roster vs. raw
-- history" split claude_usage_member/claude_usage_daily (V22) already uses,
-- for the same reason: disconnecting an integration (nulling its secret)
-- must not erase what it already did.

create table integration (
  id                        uuid        primary key default gen_random_uuid(),

  provider                  text        not null,

  -- Admin-given label, e.g. "#eng-docs Slack channel". Free text -- this is
  -- for the admin's own benefit, not parsed or matched against anything.
  name                      text        not null,

  -- Where this integration POSTs. Admin-supplied; see ADR-037 SS6 for the
  -- SSRF posture this implies and the hardening left as a decided-later item.
  target_url                text        not null,

  -- AES-256-GCM. Null once disconnected -- see the delete-path comment below.
  -- A fresh random IV per encryption, never reused, stored alongside its
  -- ciphertext rather than derived, so nothing here depends on IV uniqueness
  -- being enforced anywhere but at encryption time (SecretBox).
  signing_secret_iv         bytea,
  signing_secret_ciphertext bytea,

  -- Which app.integrations.encryption-key* encrypted the row above, so a
  -- future key rotation can tell an old row from a new one without guessing.
  encryption_key_version    smallint    not null default 1,

  -- Event-type strings this integration is subscribed to, e.g.
  -- ["account.registered"]. jsonb, not a native text[] -- this backend's one
  -- existing pattern for writing an array-shaped value from Java is Jsonb
  -- (com.coc.docs.web.Jsonb, already used by audit_log.detail), and reusing
  -- it here avoids this being the first column to also need hand-rolled
  -- java.sql.Array plumbing on the write side. Never empty by convention (an
  -- integration subscribed to nothing would never fire), but not constrained
  -- not-empty at the schema level -- IntegrationService is the one writer
  -- and enforces it there, the same division of labour email normalisation
  -- in ClaudeUsageService has with claude_usage_member.email.
  events                    jsonb       not null default '[]'::jsonb,

  -- connected: normal. disabled: the admin disconnected it -- secret columns
  -- above are nulled at that moment (cryptographically gone, not merely
  -- flagged), and the row is skipped by delivery, but integration_delivery
  -- history survives pointing at it. error: reserved for a future
  -- auto-disable-after-N-failures policy; nothing here sets it yet.
  status                    text        not null default 'connected',

  last_tested_at            timestamptz,
  last_test_status          text,

  -- Who connected it, for the audit trail's own completeness -- not itself a
  -- privilege check (SecurityConfig is). on delete set null, same reasoning
  -- V10 gives account_role.granted_by: a deleted account must not make every
  -- integration it ever connected undeletable.
  created_by                uuid        references account(id) on delete set null,

  created_at                timestamptz not null default now(),
  updated_at                timestamptz not null default now(),

  constraint integration_provider_check check (provider in ('webhook', 'slack')),
  constraint integration_status_check check (status in ('connected', 'disabled', 'error'))
);

comment on table integration is
  'Connected outgoing-webhook integrations (ADR-037). Disconnecting nulls the encrypted signing secret columns rather than deleting the row, so integration_delivery history stays attributable.';

comment on column integration.target_url is
  'Admin-supplied POST target. super_admin-only by SecurityConfig -- see ADR-037 S6 for the SSRF posture this implies.';

comment on column integration.signing_secret_ciphertext is
  'AES-256-GCM ciphertext of the signing secret this backend generated (never a third-party credential). Null once disconnected -- see IntegrationService.disconnect.';

create index integration_status_idx on integration (status);

-- GIN (jsonb_path_ops), not btree: the dispatcher's hot-path query is "which
-- connected integrations are subscribed to event X", i.e. a jsonb
-- containment check (events @> '["account.registered"]'::jsonb) rather than
-- an equality or range lookup.
create index integration_events_gin_idx on integration using gin (events jsonb_path_ops);

create table integration_delivery (
  id             bigint      generated always as identity primary key,
  integration_id uuid        not null references integration(id) on delete cascade,

  -- Includes the synthetic 'integration.test' event the "Test" button fires,
  -- alongside real event types like 'account.registered'.
  event_type     text        not null,

  http_status    int,
  success        boolean     not null,
  error          text,
  latency_ms     int,
  attempted_at   timestamptz not null default now(),

  constraint integration_delivery_http_status_check check (http_status is null or http_status between 100 and 599)
);

comment on table integration_delivery is
  'Append-only outbound-delivery attempt log for integration (ADR-037), including synthetic "Test" attempts. on delete cascade: unlike audit_log, this has no independent meaning once the integration it describes is gone for good -- but IntegrationService.disconnect only ever disables a row, never deletes it, so this cascade does not fire in normal operation.';

create index integration_delivery_integration_idx on integration_delivery (integration_id, attempted_at desc);
