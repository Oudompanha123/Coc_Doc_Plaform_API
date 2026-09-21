-- Coc Docs Platform -- rename confirmed_via's "developer_capability" value
-- to "developer_role" (ADR-039)
--
-- The capability mechanism this label referred to is retired (V31); AX
-- Renewal confirm/reopen/start-implementation is now gated on holding the
-- Developer role specifically (AppRole.exactAuthority()), not a capability.
-- Renamed to match -- no column or table shape changes.
--
--   // req: ADR-037, ADR-039

update ax_renewal_confirmation set confirmed_via = 'developer_role' where confirmed_via = 'developer_capability';

alter table ax_renewal_confirmation drop constraint ax_renewal_confirmation_confirmed_via_check;
alter table ax_renewal_confirmation add constraint ax_renewal_confirmation_confirmed_via_check
  check (confirmed_via in ('super_admin', 'developer_role'));
