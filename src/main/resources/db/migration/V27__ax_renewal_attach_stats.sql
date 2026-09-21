-- Coc Docs Platform -- AX Renewal: persist what Attach found about the folder
--
-- WHY
-- The Attach step already reads real facts about the picked folder in the
-- browser -- file count, total size, extension breakdown, lockfile presence,
-- a secrets scan of small config files, the largest file -- but today only
-- the one recognized manifest's filename/content survive the request. Every
-- other fact is discarded, so a colleague opening the project later sees
-- none of it. This is genuinely measured data (computed from the caller's
-- own files), not the fabricated sample content ADR-038 rejects, so it is
-- safe to persist as-is.
--
-- WHAT THIS IS, AND IS NOT
-- One nullable jsonb column. Nothing is parsed or validated by the database;
-- the application caps its size before writing (see AxRenewalService).
--
--   // req: ADR-037, ADR-038

alter table ax_renewal_project add column attach_stats jsonb;

comment on column ax_renewal_project.attach_stats is
  'Client-computed facts about the attached folder (file count, size, extensions, lockfile/secrets/build-output checks, largest file) -- not parsed source, and not fabricated: null until Attach runs.';
