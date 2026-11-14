-- Coc Docs Platform -- AX Renewal: the uploaded project source (ADR-118)
--
-- Attach used to send only the dependency manifest; the source itself never
-- left the browser. It is now uploaded too, as one zip archive written to the
-- backend's own disk under `file.server-path`. This column holds that file's
-- name -- the name only, never a path: where the folder is belongs to the
-- deployment's configuration, so moving it does not need a data migration.
--
-- Null until a source has been uploaded; a project attached before this
-- column existed simply has none.
--
-- req: ADR-118

alter table ax_renewal_project add column source_filename text;

comment on column ax_renewal_project.source_filename is
  'File name (not a path) of the uploaded source archive under file.server-path, e.g. <uuid>.zip. Null until uploaded.';
