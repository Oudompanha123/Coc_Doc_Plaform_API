-- Adds the local-Claude-reported directory layout (ADR-043) as a second way
-- to populate the Codebase panel's File layout section, alongside the
-- browser's own attach-time scan (ax_renewal_project.attach_stats). A
-- project attached before that scan existed has none of that data at all,
-- and re-attaching just to see this panel is not always convenient -- this
-- is the same folder, read a second way, by whichever of the two is
-- available. Same "nothing known" default as every other analysis column.
--
--   // req: ADR-043

alter table ax_renewal_analysis
  add column file_layout jsonb not null default '[]';

comment on column ax_renewal_analysis.file_layout is
  'Directories the model actually saw under source-path -- [] on the '
  'keyword-stub path or a manifest-only run. Never fabricated (ADR-043).';
