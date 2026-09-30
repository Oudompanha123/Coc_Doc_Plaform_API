-- Coc Docs Platform -- AX Renewal: project members (ADR-063)
--
-- WHY
-- Every AX Renewal project was open to change by anyone holding the right
-- role anywhere: any Admin could edit any team's plan, any Developer could
-- confirm or build any project. With several teams renewing several systems
-- at once, "who is working on this one" had no answer beyond created_by.
--
-- WHAT THIS IS, AND IS NOT
-- A plain list of accounts per project. Membership decides WHICH projects a
-- caller may change; the caller's role still decides WHAT they may do there
-- (SecurityConfig, unchanged). There is no per-project role column on
-- purpose: a Developer member confirms, an Admin member drafts, exactly as
-- before. Reading a project and commenting on it stay open to everyone who
-- could already do so -- membership gates changes only.
--
-- BACKFILL
-- Everyone who has already acted on a project becomes its member, not only
-- its creator: an existing team would otherwise be locked out of work it is
-- in the middle of the moment this applies. "Acted on" means every *_by
-- column V25 records -- creator, manifest attach, analysis, each plan
-- section's last editor, database connection, destination, section
-- confirmations, implementation runs. Commenting is deliberately not counted:
-- the thread is open to every reader, so having commented says nothing about
-- being on the team. added_by is null for these rows -- nobody added them,
-- this migration did -- and the column says so.
--
--   // req: ADR-063

create table ax_renewal_project_member (
  project_id  uuid        not null references ax_renewal_project (id) on delete cascade,
  account_id  uuid        not null references account (id) on delete cascade,
  added_by    uuid        references account (id) on delete set null,
  added_at    timestamptz not null default now(),
  primary key (project_id, account_id)
);

comment on table ax_renewal_project_member is
  'Who may change one AX Renewal project (ADR-063). Role still decides what '
  'they may do; membership decides where. Read and comment stay open.';

comment on column ax_renewal_project_member.added_by is
  'Null for rows the V58 backfill created, and for rows whose adder was '
  'since deleted -- never a guess at who it might have been.';

-- "Which projects is this account on" -- the picker's Mine filter and the
-- per-request membership check both read by account.
create index ax_renewal_project_member_account_idx on ax_renewal_project_member (account_id);

insert into ax_renewal_project_member (project_id, account_id)
select project_id, account_id
from (
  select id as project_id, created_by as account_id from ax_renewal_project
  union select id, manifest_attached_by from ax_renewal_project
  union select project_id, analyzed_by from ax_renewal_analysis
  union select project_id, updated_by from ax_renewal_stack_layer
  union select project_id, updated_by from ax_renewal_architecture_plan
  union select project_id, updated_by from ax_renewal_page_mapping
  union select project_id, updated_by from ax_renewal_db_change
  union select project_id, connected_by from ax_renewal_db_connection
  union select project_id, updated_by from ax_renewal_destination
  union select project_id, confirmed_by from ax_renewal_confirmation
  union select project_id, started_by from ax_renewal_implementation_run
) acted
where account_id is not null
on conflict do nothing;
