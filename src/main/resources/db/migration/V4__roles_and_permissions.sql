-- Coc Docs Platform -- many roles per account, many permissions per role
--
-- WHAT CHANGES
-- account.role (one enum column) becomes account_role (a set), and what a role
-- *means* moves out of the code and into role_permission.
--
-- WHAT DELIBERATELY DOES NOT CHANGE
-- The ordered ladder survives, as role.rank. Every authorisation check in this
-- system is "is the caller at least X" (FR-AUTH-005), and page.min_role stores
-- that comparison as data (FR-AUTH-006). With a set of roles the question
-- "which one do I compare?" has no answer, so the rule is fixed here: an
-- account's effective rank is the HIGHEST rank it holds. Holding an extra
-- low-ranked role can therefore never reduce access, which is the only
-- direction that would surprise someone.
--
-- Permissions are the extension point from here. Adding a role means adding an
-- app_role enum value and a rank, which is a deliberate act; adding a
-- permission is a row.
--
--   // req: FR-AUTH-005, FR-AUTH-006, FR-AUTH-008, UC-004, ADR-011, ADR-016

-- ---------------------------------------------------------------------------
-- Roles
-- ---------------------------------------------------------------------------
-- Keyed by the existing app_role enum rather than free text, so a role name
-- cannot drift apart from page.min_role, which is the same enum and must stay
-- comparable to it.
create table role (
  key         app_role    primary key,
  -- The ladder. Unique so two roles cannot silently tie, which would make
  -- "highest role held" ambiguous.
  rank        integer     not null unique,
  description text        not null
);

insert into role (key, rank, description) values
  ('guest',       0, 'Unauthenticated or unprovisioned. Sees nothing that is not public.'),
  ('user',        1, 'Reads published content.'),
  ('admin',       2, 'Authors, validates and publishes content.'),
  ('super_admin', 3, 'Everything admin does, plus who may do it.');

-- ---------------------------------------------------------------------------
-- Permissions
-- ---------------------------------------------------------------------------
-- Free text keys, dotted by area. Unlike roles these are expected to grow with
-- the product, and they are referenced by name from Java's Permission enum --
-- a key here with no constant there, or the reverse, fails a test rather than
-- silently granting or withholding.
create table permission (
  key         text        primary key,
  description text        not null
);

insert into permission (key, description) values
  ('content.read',       'Read published pages and sections.'),
  ('content.draft.edit', 'Open and edit a draft version of a page.'),
  ('content.publish',    'Publish a draft, making it the live version.'),
  ('content.validate',   'Run block-body validation against arbitrary content.'),
  ('account.read',       'List accounts and the pending registration queue.'),
  ('account.approve',    'Approve or reject a self-registered account.'),
  ('account.role.manage','Grant and revoke roles.'),
  ('account.deactivate', 'Deactivate or reactivate an account.');

-- ---------------------------------------------------------------------------
-- What each role may do
-- ---------------------------------------------------------------------------
-- Cumulative by rank, matching the ladder the application already enforces: a
-- role holds its own permissions and every permission of every lower rank.
-- Spelled out per role rather than computed, because a table you can read is
-- worth more here than one that is clever -- this is the answer to "what can an
-- admin actually do", and it should not require running a query to find out.
create table role_permission (
  role_key       app_role not null references role (key) on delete cascade,
  permission_key text     not null references permission (key) on delete cascade,
  primary key (role_key, permission_key)
);

insert into role_permission (role_key, permission_key) values
  ('user',        'content.read'),

  ('admin',       'content.read'),
  ('admin',       'content.draft.edit'),
  ('admin',       'content.publish'),
  ('admin',       'content.validate'),

  ('super_admin', 'content.read'),
  ('super_admin', 'content.draft.edit'),
  ('super_admin', 'content.publish'),
  ('super_admin', 'content.validate'),
  ('super_admin', 'account.read'),
  ('super_admin', 'account.approve'),
  ('super_admin', 'account.role.manage'),
  ('super_admin', 'account.deactivate');

-- guest holds nothing. Left as a row in role with no role_permission entries
-- rather than omitted, so "guest exists and grants nothing" is stated rather
-- than inferred from absence.

-- ---------------------------------------------------------------------------
-- Which roles each account holds
-- ---------------------------------------------------------------------------
create table account_role (
  account_id uuid        not null references account (id) on delete cascade,
  role_key   app_role    not null references role (key),
  granted_at timestamptz not null default now(),
  -- Who granted it. Null for the rows this migration backfills, and for the
  -- default role a new sign-in provisions itself -- neither had a human actor.
  granted_by uuid        references account (id),
  primary key (account_id, role_key)
);

-- Resolving the caller's roles happens on every authenticated request.
create index account_role_account_idx on account_role (account_id);

-- Backfill: every account keeps exactly the role it has today.
insert into account_role (account_id, role_key)
select id, role from account;

-- ---------------------------------------------------------------------------
-- The super_admin floor, moved
-- ---------------------------------------------------------------------------
-- The old guard read account.role, which is about to stop existing. Recreated
-- against account_role, and armed on BOTH tables: revoking the last
-- super_admin's role and deactivating the last super_admin's account are two
-- different ways to lock everyone out of account management, and a guard that
-- catches only one is a guard nobody can rely on.
--
--   // req: UC-004 E-4, FR-AUTH-008
drop trigger if exists account_super_admin_guard on account;

create or replace function assert_super_admin_remains() returns trigger
language plpgsql as $$
begin
  if not exists (
    select 1
    from account_role ar
    join account a on a.id = ar.account_id
    where ar.role_key = 'super_admin'
      and a.deactivated_at is null
  ) then
    raise exception 'at least one active super_admin must remain';
  end if;
  return null;
end;
$$;

create constraint trigger account_super_admin_guard
  after update or delete on account
  deferrable initially deferred
  for each row execute function assert_super_admin_remains();

create constraint trigger account_role_super_admin_guard
  after update or delete on account_role
  deferrable initially deferred
  for each row execute function assert_super_admin_remains();

-- ---------------------------------------------------------------------------
-- One source of truth
-- ---------------------------------------------------------------------------
-- account.role goes. Keeping it "for the transition" would leave two places
-- that answer the same question, and the one nobody updates is the one the
-- next person reads.
--
-- page.min_role keeps the app_role enum and is untouched: it is a required
-- rank, not a role assignment.
alter table account drop column role;
