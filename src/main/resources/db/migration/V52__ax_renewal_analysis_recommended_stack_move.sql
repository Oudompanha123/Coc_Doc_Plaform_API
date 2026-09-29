-- Adds the connected local model's own per-layer target-stack recommendation
-- (ADR-059), backing Plan -> Stack move's "AI recommends" hint: for a layer
-- it is confident enough about (e.g. Runtime: Node.js 12), what it would
-- suggest moving to (e.g. Node.js 22 LTS) and why -- always reviewable,
-- always optional, never applied on its own. Same "nothing known" default as
-- every other analysis column -- [] on the keyword-stub path or a
-- manifest-only run, since a real recommendation needs the same real
-- evidence `running_on_today` already requires.
--
--   // req: ADR-059

alter table ax_renewal_analysis
  add column recommended_stack_move jsonb not null default '[]';

comment on column ax_renewal_analysis.recommended_stack_move is
  'One entry per layer.running_on_today the connected model had a confident, '
  'specific successor for -- layerKey, recommendedTarget, alternativeTargets, '
  'why. [] on the keyword-stub path or a manifest-only run. A hint Stack move '
  'shows and lets a person accept, change, or ignore -- never written as the '
  'plan on its own (ADR-059).';
