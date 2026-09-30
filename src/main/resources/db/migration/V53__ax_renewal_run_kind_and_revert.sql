-- Coc Docs Platform -- AI Error Assistant / UI Customization, Phase 2 (ADR-060)
--
-- ax_renewal_implementation_run gains:
--   run_kind    -- 'plan' (the only kind that has ever existed; the default,
--                  so every existing row keeps meaning exactly what it
--                  already meant), 'error_fix' (an approved fix for a real
--                  Run Locally failure) or 'ui_change' (a natural-language
--                  UI change against the renewed project's own UI, never
--                  this admin app's own).
--   instruction -- the raw input a plan run never needed (it rebuilds its
--                  own prompt from the confirmed Plan tables instead): the
--                  real captured Run Locally failure log for error_fix, or
--                  the person's own free-text description for ui_change.
--                  Null for plan.
--
-- status widens to add 'reverted' -- a specific applied run undone on
-- purpose, kept distinct from every other terminal status for the same
-- reason 'cancelled'/'rejected' already are (V46/V42): a deliberate action
-- is a different fact from the run having failed or been rejected. Revert
-- itself needs no new table -- the files to restore are exactly the rows
-- already in ax_renewal_generated_change for that run.
--
--   // req: ADR-060

alter table ax_renewal_implementation_run
  add column run_kind    text not null default 'plan'
    check (run_kind in ('plan', 'error_fix', 'ui_change')),
  add column instruction text,
  drop constraint ax_renewal_implementation_run_status_check,
  add constraint ax_renewal_implementation_run_status_check
    check (status in (
      'running', 'completed', 'failed',
      'generating', 'awaiting_review', 'applying', 'applied', 'rejected', 'cancelled', 'reverted'
    ));

comment on column ax_renewal_implementation_run.run_kind is
  'plan (a confirmed Plan carried out), error_fix (an approved fix for a '
  'real Run Locally failure) or ui_change (a natural-language UI change '
  'against the renewed project''s own UI) -- ADR-060 Phase 2.';

comment on column ax_renewal_implementation_run.instruction is
  'the raw input this run was generated from, for error_fix/ui_change only '
  '-- the real captured failure log, or the person''s own free-text '
  'description. Null for plan, which rebuilds its own prompt from the '
  'confirmed Plan tables instead of storing one.';
