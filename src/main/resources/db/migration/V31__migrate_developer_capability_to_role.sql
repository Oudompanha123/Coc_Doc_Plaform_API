-- Coc Docs Platform -- Developer becomes a role; retires the capability
-- mechanism entirely (ADR-039, revising ADR-037/038)
--
-- WHY
-- The PM asked for "Developer" to be a real, selectable role rather than the
-- separate capability grant ADR-037 introduced. Since "developer" was the
-- only capability ever seeded (V24) and the only consumer of the
-- capability/account_capability mechanism, retiring it here means retiring
-- the mechanism outright, not leaving an empty, permanently-404ing framework
-- behind. AX Renewal's confirm/reopen/start-implementation gate, which used
-- to check "holds the developer capability", now checks "holds the developer
-- role exactly" (AppRole.exactAuthority(), SecurityConfig; see AppRole's own
-- Javadoc for why rank alone -- hasRole() -- cannot express this: an Admin
-- must NOT pass this gate merely by out-ranking Developer).
--
-- RANKS
-- role.rank is UNIQUE (V4) and drives the /api/v1/admin/roles catalogue's
-- display order -- it must agree with the real ladder (AppRole's Java
-- declaration order). Inserting developer between user(1) and reviewer(2)
-- means reviewer/lead/admin/super_admin all shift up by one first, highest
-- first to avoid a transient collision with the unique constraint.
--
--   // req: ADR-037, ADR-038, ADR-039, FR-AUTH-005, FR-AUTH-006, ADR-016

update role set rank = 6 where key = 'super_admin';
update role set rank = 5 where key = 'admin';
update role set rank = 4 where key = 'lead';
update role set rank = 3 where key = 'reviewer';

insert into role (key, rank, description) values
  ('developer', 2, 'Implements AX Renewal plans. Grants content.read; AX Renewal confirm/reopen/'
                    'start-implementation is gated on holding this role specifically (or Super Admin), '
                    'not on rank -- see AppRole.exactAuthority().');

insert into role_permission (role_key, permission_key) values
  ('developer', 'content.read');

-- Migrate: every account that held the capability now holds the role
-- instead. ON CONFLICT DO NOTHING covers an account that already happened to
-- hold some other role at rank 2 -- impossible today, but the safe default
-- for a data migration regardless.
insert into account_role (account_id, role_key, granted_by)
select account_id, 'developer', granted_by from account_capability where capability_key = 'developer'
on conflict (account_id, role_key) do nothing;

-- The mechanism is retired, not just this one grant: no code references
-- capability/account_capability any more (Capability, CapabilityService,
-- CapabilityRepository deleted in this change), and no second capability was
-- ever added in the time this existed.
drop table account_capability;
drop table capability;
