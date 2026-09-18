-- Coc Docs Platform -- AX Renewal: persisted legacy-migration planning
--
-- WHY
-- Replaces a standalone prototype's in-memory browser state with real,
-- persisted, role-gated resources: a project's attached manifest, its
-- detected/target stack per architectural layer, its old/new page mapping,
-- its database change plan, its four gated confirmations, its output
-- destination, and its implementation runs. See ADR-037.
--
-- PHASE 1 SCOPE (deliberate, not an oversight)
-- No live call to a third-party AI leaves this backend yet: ax_renewal_analysis
-- is filled by a deterministic, no-network stub, and model_connected is a real
-- column rather than inferred, so a later phase's genuine analyses are
-- structurally distinguishable from today's. ax_renewal_db_connection is
-- metadata only -- no credential column -- because this phase does not open a
-- live connection to a third-party database at all.
--
-- AUDIT
-- Every mutating action is written to the existing, shared audit_log via
-- AuditRepository -- no separate audit table here. What still needs a
-- dedicated table is the plain "who confirmed this and when" fact
-- (ax_renewal_confirmation), which a viewer at the `user` floor may read
-- directly rather than through the super_admin-only audit trail.
--
--   // req: ADR-037

create table ax_renewal_project (
  id                    uuid        primary key default gen_random_uuid(),
  name                  text        not null,
  description           text,
  status                text        not null default 'draft'
                          check (status in ('draft','analyzed','planning','ready','implementing','completed')),
  manifest_filename     text,
  manifest_content      text,
  manifest_attached_at  timestamptz,
  manifest_attached_by  uuid        references account (id),
  created_by            uuid        not null references account (id),
  created_at            timestamptz not null default now(),
  updated_at            timestamptz not null default now()
);

comment on table ax_renewal_project is 'One legacy-renewal planning workspace (ADR-037).';

create table ax_renewal_analysis (
  project_id      uuid        primary key references ax_renewal_project (id) on delete cascade,
  stack_detected  jsonb       not null default '{}',
  findings        jsonb       not null default '[]',
  pages_detected  jsonb       not null default '[]',
  model_connected boolean     not null default false,
  analyzed_at     timestamptz not null default now(),
  analyzed_by     uuid        not null references account (id)
);

comment on column ax_renewal_analysis.model_connected is
  'Always false in Phase 1 -- no third-party AI call exists yet (ADR-037). '
  'A real column, not inferred, so a later phase''s genuine analyses are '
  'structurally distinguishable from today''s deterministic stub.';

create table ax_renewal_stack_layer (
  id          uuid        primary key default gen_random_uuid(),
  project_id  uuid        not null references ax_renewal_project (id) on delete cascade,
  layer_key   text        not null,
  old_target  text,
  new_target  text,
  position    integer     not null default 0,
  updated_at  timestamptz not null default now(),
  updated_by  uuid        references account (id),
  constraint ax_renewal_stack_layer_key unique (project_id, layer_key)
);

create table ax_renewal_architecture_plan (
  project_id  uuid        primary key references ax_renewal_project (id) on delete cascade,
  summary     text,
  diagram     jsonb,
  updated_at  timestamptz not null default now(),
  updated_by  uuid        references account (id)
);

create table ax_renewal_page_mapping (
  id          uuid        primary key default gen_random_uuid(),
  project_id  uuid        not null references ax_renewal_project (id) on delete cascade,
  old_path    text,
  new_path    text,
  notes       text,
  position    integer     not null default 0,
  updated_at  timestamptz not null default now(),
  updated_by  uuid        references account (id),
  constraint ax_renewal_page_mapping_has_a_side check (old_path is not null or new_path is not null)
);

create table ax_renewal_db_change (
  id           uuid        primary key default gen_random_uuid(),
  project_id   uuid        not null references ax_renewal_project (id) on delete cascade,
  change_type  text        not null check (change_type in ('add_column','add_index')),
  table_name   text        not null,
  detail       jsonb       not null,
  rationale    text,
  position     integer     not null default 0,
  updated_at   timestamptz not null default now(),
  updated_by   uuid        references account (id)
);

comment on constraint ax_renewal_db_change_change_type_check on ax_renewal_db_change is
  'The structural enforcement of "additive-only, never table create/drop" '
  '(ADR-037) -- not a service-layer opinion, a database constraint.';

create table ax_renewal_db_connection (
  project_id      uuid        primary key references ax_renewal_project (id) on delete cascade,
  engine          text,
  host            text,
  port            integer,
  database_name   text,
  schema_name     text,
  schema_snapshot jsonb,
  connected_at    timestamptz,
  connected_by    uuid        references account (id)
);

comment on table ax_renewal_db_connection is
  'Metadata only, deliberately -- no credential column. Phase 1 does not '
  'open a live connection to a third-party database (ADR-037).';

create table ax_renewal_destination (
  project_id  uuid        primary key references ax_renewal_project (id) on delete cascade,
  kind        text        not null check (kind in ('git_repo','filesystem_path','other')),
  target      text        not null,
  branch      text,
  notes       text,
  updated_at  timestamptz not null default now(),
  updated_by  uuid        references account (id)
);

create table ax_renewal_confirmation (
  project_id      uuid        not null references ax_renewal_project (id) on delete cascade,
  section         text        not null check (section in ('stack_move','architecture','pages','database')),
  confirmed_at    timestamptz not null default now(),
  confirmed_by    uuid        not null references account (id),
  confirmed_via   text        not null check (confirmed_via in ('super_admin','developer_capability')),
  primary key (project_id, section)
);

comment on table ax_renewal_confirmation is
  'One row per confirmed section; absence means unconfirmed. Deleted by '
  'AxRenewalService whenever that section''s content is edited afterwards, '
  'so a stale confirmation can never survive a plan change silently.';

create table ax_renewal_implementation_run (
  id             uuid        primary key default gen_random_uuid(),
  project_id     uuid        not null references ax_renewal_project (id) on delete cascade,
  status         text        not null default 'running' check (status in ('running','completed','failed')),
  before_report  jsonb,
  after_report   jsonb,
  started_at     timestamptz not null default now(),
  started_by     uuid        not null references account (id),
  completed_at   timestamptz
);

create table ax_renewal_comment (
  id          uuid        primary key default gen_random_uuid(),
  project_id  uuid        not null references ax_renewal_project (id) on delete cascade,
  author_id   uuid        not null references account (id),
  body        text        not null,
  created_at  timestamptz not null default now()
);

create index ax_renewal_stack_layer_project_idx on ax_renewal_stack_layer (project_id);
create index ax_renewal_page_mapping_project_idx on ax_renewal_page_mapping (project_id);
create index ax_renewal_db_change_project_idx on ax_renewal_db_change (project_id);
create index ax_renewal_comment_project_idx on ax_renewal_comment (project_id);
create index ax_renewal_implementation_run_project_idx on ax_renewal_implementation_run (project_id);
