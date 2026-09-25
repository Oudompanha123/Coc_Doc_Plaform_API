-- Coc Docs Platform -- Cancel a running Generate attempt (ADR-049)
--
-- Widens ax_renewal_implementation_run.status to add 'cancelled' -- kept
-- distinct from 'failed' on purpose, the same reason 'rejected' already is:
-- a deliberate stop by the person watching it is a different fact from
-- generation genuinely breaking on its own, and conflating the two would
-- misreport what happened to anyone reading the run's history later.
--
--   // req: ADR-049

alter table ax_renewal_implementation_run
  drop constraint ax_renewal_implementation_run_status_check,
  add constraint ax_renewal_implementation_run_status_check
    check (status in (
      'running', 'completed', 'failed',
      'generating', 'awaiting_review', 'applying', 'applied', 'rejected', 'cancelled'
    ));
