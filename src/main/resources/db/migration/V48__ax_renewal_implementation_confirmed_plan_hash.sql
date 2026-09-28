-- Coc Docs Platform -- Rebuild reproduces the same result deterministically (ADR-053)
--
-- WHY
-- "Rebuild" used to mean "ask the model again" every single time, which is
-- two different projects' worth of non-determinism stacked on top of each
-- other: a fresh AI generation run does not reliably produce the same
-- output twice, and a second team member's own machine has its own legacy
-- checkout at its own path, possibly not byte-identical to the first
-- person's. Once a project has a real, human-approved result
-- (STATUS_APPLIED), every other team member clicking Rebuild should get
-- that exact result, not a new roll of the dice.
--
-- confirmed_plan_hash is a SHA-256 of the same confirmed-plan text
-- AxRenewalService already builds to prompt the model
-- (buildConfirmedPlanDescription) -- recorded on every real Generate run, so
-- a later Rebuild can tell "the confirmed plan is still exactly what
-- produced this applied run" from "something was edited and reconfirmed
-- since", without re-deriving history from confirmation timestamps.
--
--   // req: ADR-044, ADR-053

alter table ax_renewal_implementation_run
  add column confirmed_plan_hash text;

comment on column ax_renewal_implementation_run.confirmed_plan_hash is
  'sha-256 hex of the confirmed-plan text this run was generated from -- '
  'null for a Phase-1 placeholder run, which never builds one. Compared at '
  'Rebuild time to decide whether a prior applied run''s exact generated '
  'changes can be replayed rather than asking the model again (ADR-053).';
