-- Soft-delete for AX Renewal project comments.
--
-- A comment was write-once: no edit, no delete, no endpoint for either. That
-- is unforgiving about the ordinary case (posted to the wrong project, a typo
-- somebody would rather not leave for the team) without protecting anything
-- in particular.
--
-- Soft, not hard, and no edit at all: the row stays, so what was said cannot
-- be quietly rewritten after a discussion has moved on, and the audit log's
-- comment_add entry still refers to something that exists. Hiding a comment
-- is the reversible half; removing the history is not on offer.
--
-- req: ADR-037, ADR-045

alter table ax_renewal_comment
  add column deleted_at timestamptz,
  add column deleted_by uuid references account (id);

-- Both or neither: a row marked deleted with nobody attached to the decision
-- would leave the audit trail unable to answer "who". Enforced here rather
-- than only in Java, because the table outlives any one caller.
alter table ax_renewal_comment
  add constraint ax_renewal_comment_deleted_pair
  check ((deleted_at is null) = (deleted_by is null));

-- The listing reads live comments for one project, in time order. Partial, so
-- deleted rows do not grow the index that every read goes through.
create index ax_renewal_comment_live_idx
  on ax_renewal_comment (project_id, created_at)
  where deleted_at is null;
