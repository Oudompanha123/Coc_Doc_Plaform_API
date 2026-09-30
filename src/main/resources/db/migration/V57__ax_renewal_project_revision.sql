-- Coc Docs Platform -- AX Renewal: stale-edit protection for a shared plan (ADR-061)
--
-- WHY
-- Every plan edit (stack move, architecture, pages, database plan, database
-- connection, destination) replaces its whole section. With more than one
-- person working on a project that meant last-write-wins with no warning:
-- two people with the Pages section open, and the second save silently
-- erased the first. Same failure FR-CONT-013 already closed for page drafts
-- with page_version.version_no (ADR-003), closed here the same way.
--
-- WHAT
-- One counter per project, not one per section. Every plan edit must send
-- back the revision it loaded; the edit bumps it, and a mismatch is a 409
-- the editor sees, not a silent overwrite. Confirming a section checks it
-- too (a sign-off on content the confirmer has not seen is exactly the
-- stale write this exists to refuse) but does not bump it -- a confirmation
-- changes no plan content.
--
-- Coarse on purpose: an edit to Pages also refuses a concurrent Stack edit
-- that did not actually overlap. At team scale that is a reload, and it keeps
-- this to one column instead of a version on every plan table.
--
-- Default 0 so every existing project starts at a valid revision without a
-- backfill.
--
--   // req: ADR-061, FR-CONT-013

alter table ax_renewal_project
  add column revision integer not null default 0;

comment on column ax_renewal_project.revision is
  'Optimistic lock over the whole plan (ADR-061): bumped by every plan edit, '
  'checked by every plan edit and by section confirmation. A stale value is '
  'refused with a 409, never silently overwritten.';
