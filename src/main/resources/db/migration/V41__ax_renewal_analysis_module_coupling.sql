-- Adds the local-Claude-traced module import graph (ADR-043), backing the
-- Architecture panel's "Module coupling" diagram: which files import which,
-- which are hubs (imported by several others), and which pairs import each
-- other. Same "nothing known" default as every other analysis column --
-- {} on the keyword-stub path or a manifest-only run, since tracing real
-- imports needs the actual source.
--
--   // req: ADR-043

alter table ax_renewal_analysis
  add column module_coupling jsonb not null default '{}';

comment on column ax_renewal_analysis.module_coupling is
  'Which modules import which, as traced by a connected local model reading '
  'real source -- {} on the keyword-stub path or a manifest-only run. '
  'Never fabricated (ADR-043).';
