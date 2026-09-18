-- Coc Docs Platform -- account capabilities: a second, rank-independent grant
--
-- WHY
-- ADR-016 kept one ladder (guest < user < admin < super_admin) for content
-- sensitivity (page.min_role) and made permissions the extension point for
-- actions. It also named, as unfinished follow-up work, a kind of grant that
-- fits neither: a capability layered on top of whichever role an account
-- already holds, that must never require altering the app_role enum or its
-- rank -- because that enum is also page.min_role's type, and a change there
-- is a change to content-visibility rules platform-wide, not just to one
-- feature (AX Renewal's "Developer" capability, ADR-037).
--
-- WHAT THIS IS, AND IS NOT
-- capability / account_capability mirror the shape of permission /
-- role_permission (V4), not role / account_role: a capability has no rank
-- and is granted directly to an account, never through a role. Deliberately
-- not named capability_role or account_capability_role -- naming it like the
-- role ladder would re-introduce exactly the coupling this migration exists
-- to avoid.
--
-- No capability-to-permission mapping table yet. With exactly one capability
-- today, a consuming service checks "does this account hold capability X"
-- directly; a mapping layer is deferred until a second capability actually
-- needs one (ADR-037).
--
--   // req: ADR-016, ADR-037

create table capability (
  key         text        primary key,
  description text        not null,
  created_at  timestamptz not null default now()
);

comment on table capability is
  'A capability catalog, parallel to but independent of the app_role ladder '
  'and the permission/role_permission tables (ADR-016). Never backed by the '
  'app_role Postgres enum -- see ADR-037.';

insert into capability (key, description) values
  ('developer', 'May confirm an AX Renewal plan section and start implementation, independent of app_role rank.');

-- Shaped like account_role (V4), but capability_key is text, not app_role: an
-- account may hold a capability at any app_role, including guest, which is
-- the whole point of decoupling it from the ladder.
create table account_capability (
  account_id     uuid        not null references account (id) on delete cascade,
  capability_key text        not null references capability (key),
  granted_at     timestamptz not null default now(),
  granted_by     uuid        references account (id),
  primary key (account_id, capability_key)
);

comment on table account_capability is
  'Direct account-to-capability grants. No rank, no role_key -- an account '
  'may hold a capability at any app_role.';

create index account_capability_account_idx on account_capability (account_id);
