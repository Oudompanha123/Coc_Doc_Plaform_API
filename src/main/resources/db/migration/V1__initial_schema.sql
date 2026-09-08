-- Coc Docs Platform — initial schema
-- Sprint 1 / T2.  Implements docs/design/adr/ADR-003-persistence-strategy.md
--
-- This schema exists largely to buy back five properties that content-in-git
-- provides for free and that migrating to a database silently removes:
--   version history      -> page_version rows            // req: FR-CONT-012
--   concurrent safety    -> version_no optimistic lock   // req: FR-CONT-013
--   ordering integrity   -> one order column             // req: FR-NAV-004
--   translation coverage -> derivable, not compiler-enforced  // req: NFR-I18N-01
--   backup               -> operational, see NFR-OPS-BACKUP


-- ---------------------------------------------------------------------------
-- Enums
-- ---------------------------------------------------------------------------

-- Ordered role ladder: guest < user < admin < super_admin   // req: FR-AUTH-005
create type app_role as enum ('guest', 'user', 'admin', 'super_admin');

create type version_status as enum ('draft', 'published', 'archived');

create type locale as enum ('en', 'km', 'ko');

-- ---------------------------------------------------------------------------
-- Accounts                                                  // req: FR-AUTH-003
-- ---------------------------------------------------------------------------
-- No password column, by design. Authentication is Google OIDC and the system
-- stores no credentials.                                    // req: FR-AUTH-002
create table account (
  id             uuid primary key default gen_random_uuid(),
  email          text        not null unique,
  display_name   text        not null,
  role           app_role    not null default 'user',
  -- Deactivate, never delete: authored content and audit entries keep their
  -- attribution after someone leaves.                       // req: FR-AUTH-003
  deactivated_at timestamptz,
  created_at     timestamptz not null default now(),
  updated_at     timestamptz not null default now()
);

create index account_active_idx on account (email) where deactivated_at is null;

-- At least one active super_admin must always exist, or the system locks itself
-- out of account management.                                 // req: UC-004 E-4
create or replace function assert_super_admin_remains() returns trigger
language plpgsql as $$
begin
  if not exists (
    select 1 from account
    where role = 'super_admin' and deactivated_at is null
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

-- ---------------------------------------------------------------------------
-- Sections and pages
-- ---------------------------------------------------------------------------
-- A section is a doc set (jex3.0, jex2.0, coach, idea, workflow).
create table section (
  id           uuid primary key default gen_random_uuid(),
  slug         text        not null unique,
  "order"      integer     not null,
  -- Migration coexistence: while false the section still renders from
  -- content/*.tsx. Flipping one flag migrates one doc set, and flipping it back
  -- is the per-section rollback.              // req: CONST-OPS-03, DEV-PLAN §5
  db_backed    boolean     not null default false,
  created_at   timestamptz not null default now()
);

-- A page is ONE thing that exists in three languages, not three pages.
-- Locale lives on page_version, never here.
create table page (
  id           uuid primary key default gen_random_uuid(),
  section_id   uuid        not null references section(id) on delete restrict,
  slug         text        not null,
  -- The single authoritative ordering value. Replaces three hand-synchronised
  -- arrays (lib/i18n.ts, Sidebar.tsx, content-file key order) that nothing but
  -- developer discipline kept in agreement.                  // req: FR-NAV-004
  "order"      integer     not null,
  badge        text,
  -- Minimum role required to view. Defined in MVP, fully enforced in phase 2.
  min_role     app_role    not null default 'user',           -- req: FR-AUTH-006
  deleted_at   timestamptz,                                   -- req: FR-CONT-007
  created_at   timestamptz not null default now(),
  unique (section_id, slug)
);

create index page_section_order_idx on page (section_id, "order") where deleted_at is null;

-- ---------------------------------------------------------------------------
-- Page versions — draft and published coexist as ROWS, not as a flag
-- ---------------------------------------------------------------------------
-- Modelling both as rows means saving a draft cannot affect what readers see,
-- publishing is a status transition rather than an overwrite, and version
-- history falls out for free.        // req: FR-CONT-003, FR-CONT-005, AMB-001
create table page_version (
  id                uuid           primary key default gen_random_uuid(),
  page_id           uuid           not null references page(id) on delete cascade,
  locale            locale         not null,
  status            version_status not null default 'draft',

  title             text           not null,
  description       text           not null default '',
  -- Ordered array of typed blocks (ADR-010). jsonb, not text: queryable,
  -- indexable, and validated against the block schema on write.
  body              jsonb          not null default '[]'::jsonb,

  -- Optimistic lock. The editor sends the version_no it loaded; a mismatch is
  -- refused rather than silently overwriting a colleague's work.
  version_no        integer        not null default 1,        -- req: FR-CONT-013

  -- Which published `en` version this translation was made from. Staleness is
  -- DERIVED from this, never stored as a mutable flag, so it cannot drift out
  -- of sync with reality.                     // req: FR-I18N-002, ADR-003 §5
  source_version_id uuid           references page_version(id) on delete set null,

  created_by        uuid           references account(id) on delete set null,
  created_at        timestamptz    not null default now(),
  updated_at        timestamptz    not null default now(),

  constraint body_is_array check (jsonb_typeof(body) = 'array')
);

-- At most one published row per (page, locale) — enforced by the database,
-- not by application code.
create unique index page_version_one_published_idx
  on page_version (page_id, locale)
  where status = 'published';

-- At most one draft row per (page, locale): a draft is a working copy.
create unique index page_version_one_draft_idx
  on page_version (page_id, locale)
  where status = 'draft';

create index page_version_lookup_idx on page_version (page_id, locale, status);

-- ---------------------------------------------------------------------------
-- Slug history — renaming a page must not break inbound links
-- ---------------------------------------------------------------------------
-- // req: FR-READ-003, CONST-OPS-02
create table page_slug_history (
  id          uuid        primary key default gen_random_uuid(),
  page_id     uuid        not null references page(id) on delete cascade,
  section_id  uuid        not null references section(id) on delete cascade,
  old_slug    text        not null,
  changed_at  timestamptz not null default now(),
  unique (section_id, old_slug)
);

-- ---------------------------------------------------------------------------
-- Audit trail                                    // req: FR-OPS-001, ADR-006
-- ---------------------------------------------------------------------------
-- Append-only by application discipline; no UPDATE or DELETE path is exposed.
-- Retained at least 1 year.                                // req: NFR-OPS-AUDIT
create table audit_log (
  id          bigserial   primary key,
  actor_id    uuid        references account(id) on delete set null,
  actor_email text        not null,      -- denormalised: survives account deletion
  action      text        not null,      -- e.g. page.publish, account.role_change
  target_type text        not null,
  target_id   uuid,
  detail      jsonb       not null default '{}'::jsonb,
  occurred_at timestamptz not null default now()
);

create index audit_log_target_idx   on audit_log (target_type, target_id, occurred_at desc);
create index audit_log_occurred_idx on audit_log (occurred_at desc);

revoke update, delete on audit_log from public;

-- ---------------------------------------------------------------------------
-- Derived views
-- ---------------------------------------------------------------------------

-- Translation coverage and staleness.
--   coverage  = a published row exists in all three locales  (NFR-I18N-01)
--   staleness = published translation whose en source has since moved
-- These are deliberately DIFFERENT measures: staleness is not a coverage
-- breach. See AMB-002.                        // req: FR-I18N-003, NFR-I18N-01
create view translation_status as
with published as (
  select pv.page_id, pv.locale, pv.id, pv.source_version_id
  from page_version pv
  where pv.status = 'published'
),
en_current as (
  select page_id, id as en_version_id from published where locale = 'en'
)
select
  p.id                       as page_id,
  s.slug                     as section_slug,
  p.slug                     as page_slug,
  l.locale,
  (pub.id is not null)       as exists_published,
  case
    when l.locale = 'en'            then false
    when pub.id is null             then false          -- missing, not stale
    when e.en_version_id is null    then false          -- no en to compare
    else pub.source_version_id is distinct from e.en_version_id
  end                        as is_stale
from page p
join section s on s.id = p.section_id
cross join (select unnest(enum_range(null::locale)) as locale) l
left join published pub on pub.page_id = p.id and pub.locale = l.locale
left join en_current e   on e.page_id = p.id
where p.deleted_at is null;

