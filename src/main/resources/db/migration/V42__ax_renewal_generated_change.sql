-- Generate -> Review -> Apply for AX Renewal Implementation (ADR-044).
--
-- ax_renewal_implementation_run gains two columns: staging_path (where a
-- Generate run's disposable working copy lives, so Apply/cleanup can find it
-- again) and failure_reason (why a run failed, honestly recorded rather than
-- left for someone to guess at from a bare "failed" status). Its status
-- constraint widens to the new intermediate states; the four existing values
-- are untouched, so nothing about the current honest-completion-report path
-- (no local model, or a non-filesystem destination) changes at all.
--
-- ax_renewal_generated_change is new: one row per file a Generate run
-- actually found different between the untouched attached source and its
-- own staged copy -- before_content/after_content are the real, full
-- contents of each side (null on whichever side doesn't apply: before is
-- null for a created file, after is null for a deleted one), not a
-- computed patch format. Nothing here is invented; a run that changed
-- nothing has zero rows, not a fabricated one.
--
--   // req: ADR-044

alter table ax_renewal_implementation_run
  drop constraint if exists ax_renewal_implementation_run_status_check,
  add constraint ax_renewal_implementation_run_status_check
    check (status in (
      'running', 'completed', 'failed',
      'generating', 'awaiting_review', 'applying', 'applied', 'rejected'
    )),
  add column staging_path   text,
  add column failure_reason text;

create table ax_renewal_generated_change (
  id             uuid        primary key default gen_random_uuid(),
  run_id         uuid        not null references ax_renewal_implementation_run (id) on delete cascade,
  path           text        not null,
  change_kind    text        not null check (change_kind in ('create', 'modify', 'delete')),
  before_content text,
  after_content  text,
  position       integer     not null default 0
);

create index ax_renewal_generated_change_run_idx on ax_renewal_generated_change (run_id);

comment on table ax_renewal_generated_change is
  'One real proposed file change per row from a Generate run -- before/after '
  'content, never a fabricated summary. Reviewed as a whole run (approve or '
  'reject all of it) before Apply copies anything into the real destination '
  '(ADR-044).';
