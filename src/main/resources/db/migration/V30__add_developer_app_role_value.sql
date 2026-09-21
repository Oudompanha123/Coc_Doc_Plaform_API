-- Coc Docs Platform -- extend app_role with Developer (ADR-039)
--
-- Retires the rank-independent "developer" capability (ADR-037/V24) in favor
-- of a real role, strictly between user and reviewer:
-- guest < user < developer < reviewer < lead < admin < super_admin.
--
-- Split into its own migration, committed before V31 seeds rows using this
-- value: Postgres refuses to use a newly added enum label inside the same
-- transaction that added it, so a single combined migration would fail at
-- V31's insert.
--
--   // req: ADR-037, ADR-039, FR-AUTH-005, FR-AUTH-006

alter type app_role add value 'developer' after 'user';
