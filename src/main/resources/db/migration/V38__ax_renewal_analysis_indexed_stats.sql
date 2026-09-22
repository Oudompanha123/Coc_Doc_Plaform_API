-- Adds the two fields the local-Claude analysis path (ADR-043) can supply
-- that the deterministic keyword stub never could: rough counts a model
-- reading real source can support (indexed) and an honest description of
-- what the project runs on today (running_on_today). Both default to the
-- same "nothing known" shape every other analysis column already uses
-- (jsonb object/array, never null) so a keyword-stub row and a
-- not-yet-analysed project stay indistinguishable from these two columns
-- alone, exactly like stack_detected/findings/pages_detected already are.
--
-- File count and language shares deliberately do NOT get columns here --
-- they come from ax_renewal_project.attach_stats, which the browser already
-- measures for real at attach time, with no model involved at all.
--
--   // req: ADR-043

alter table ax_renewal_analysis
  add column indexed          jsonb not null default '{}',
  add column running_on_today jsonb not null default '[]';

comment on column ax_renewal_analysis.indexed is
  'lines/routes/templates/dependencies/testCoveragePercent, each present only '
  'when a connected local model could support it with real evidence -- {} on '
  'the keyword-stub path. Never fabricated (ADR-043).';

comment on column ax_renewal_analysis.running_on_today is
  'What the project visibly runs on today, each entry an observation with an '
  'optional caution note -- not a migration target, which stays the Plan '
  'section''s own data. [] on the keyword-stub path (ADR-043).';
