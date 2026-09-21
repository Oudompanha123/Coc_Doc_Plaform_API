-- Coc Docs Platform -- extend app_role with Reviewer and Lead (ADR-039)
--
-- Two real, assignable roles added to the ladder strictly between user and
-- admin: guest < user < reviewer < lead < admin < super_admin. Distinct from
-- the "developer" capability (ADR-037/V24), which is deliberately NOT
-- duplicated here as a role -- it stays a rank-independent grant, unchanged.
--
-- Split into its own migration, committed before V29 seeds rows using these
-- values: Postgres refuses to use a newly added enum label inside the same
-- transaction that added it, so a single combined migration would fail at
-- V29's insert.
--
--   // req: ADR-039, FR-AUTH-005, FR-AUTH-006

alter type app_role add value 'reviewer' after 'user';
alter type app_role add value 'lead' after 'reviewer';
