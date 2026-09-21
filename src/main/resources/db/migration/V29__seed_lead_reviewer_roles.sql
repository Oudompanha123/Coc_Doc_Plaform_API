-- Coc Docs Platform -- seed Reviewer and Lead into the role ladder (ADR-039)
--
-- role.rank drives the /api/v1/admin/roles catalog's display order
-- (RoleRepository.findAll, "order by rank") and must agree with the real
-- authorisation ladder (AppRole's Java declaration order): guest(0) <
-- user(1) < reviewer(2) < lead(3) < admin(4) < super_admin(5). rank is
-- UNIQUE (V4), so admin and super_admin are renumbered first to free 2 and 3
-- -- there is no gap between them and user today.
--
-- Neither role is granted anything beyond content.read (the same as a plain
-- user) yet. Every /api/v1/admin/** route still floors at ADMIN
-- (SecurityConfig, unchanged), so today these exist as real, orderable
-- roles without unlocking any action a user could not already do -- see
-- AppRole's own Javadoc. What, if anything, they should unlock beyond that
-- is left for a future decision once a concrete feature needs it, the same
-- deferral V24/ADR-037 already made once for capability-to-permission
-- mapping.
--
--   // req: ADR-039, FR-AUTH-005, FR-AUTH-006

update role set rank = 4 where key = 'admin';
update role set rank = 5 where key = 'super_admin';

insert into role (key, rank, description) values
  ('reviewer', 2, 'Reviews content and flags issues before publish. Grants no permission beyond content.read yet.'),
  ('lead',     3, 'Leads a team''s content decisions, ranked above Reviewer. Grants no permission beyond content.read yet.');

insert into role_permission (role_key, permission_key) values
  ('reviewer', 'content.read'),
  ('lead',     'content.read');
