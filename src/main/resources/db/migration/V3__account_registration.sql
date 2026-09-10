-- Coc Docs Platform — password self-registration + approval workflow
-- Implements ADR-014 (revision of ADR-008): adds a second, password-based
-- sign-in path alongside Google/Keycloak OIDC, gated by super_admin
-- approval.                                    // req: FR-AUTH-002, FR-AUTH-003

-- Registration/approval lifecycle. Independent of `deactivated_at`, which
-- separately answers "was this account active and later turned off" —
-- `status` answers "was this registration ever approved to sign in at all".
-- Only an 'active' account (either provisioning path) may sign in.
create type account_status as enum ('pending', 'active', 'rejected');

-- Existing rows (all Google/Keycloak-provisioned to date) default to
-- 'active', so today's sign-in behaviour is unaffected by this migration.
alter table account add column status account_status not null default 'active';

-- Null for accounts that only ever sign in via Google/Keycloak. Always a
-- bcrypt hash when set — the application never stores anything else here,
-- and never reads this column back into any API response.
alter table account add column password_hash text;

comment on column account.status is
  'pending: awaiting super_admin review (self-registered). active: may sign in. rejected: registration refused, may never sign in. // req: FR-AUTH-003, ADR-014';
comment on column account.password_hash is
  'bcrypt hash for the password self-registration path (ADR-014). Null for accounts that only use Google/Keycloak OIDC.';

-- Lets the admin-approval listing (GET /api/admin/accounts/pending) find
-- and order the (expected to be small) working set without scanning every
-- account row — same reasoning as the existing account_active_idx.
create index account_pending_idx on account (created_at) where status = 'pending';
