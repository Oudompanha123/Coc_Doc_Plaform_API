-- Coc Docs Platform — make an account deletable again, whatever it worked on
-- Revises ADR-023's schema half, the way V10 did once before.
--                                        // req: FR-AUTH-003, FR-AUTH-008, ADR-023, ADR-094
--
-- Deleting an account from Users & Roles failed with "an unexpected error
-- occurred" for anyone who had ever done work here. Fifteen references to
-- account(id) were declared with no ON DELETE action, so PostgreSQL defaults
-- them to NO ACTION and refuses the delete:
--
--   update or delete on table "account" violates foreign key constraint
--   "ax_renewal_comment_author_id_fkey" on table "ax_renewal_comment"
--
-- V10 fixed exactly this for account_role.granted_by. These came later
-- (V13 docs content, V22 Claude usage, V25/V45 AX Renewal) and repeated the
-- omission: every one is a "who did this" column on work that belongs to the
-- team, not to the person.
--
-- ADR-023's rule, applied the same way to all of them: the work stays, the
-- name on it goes — SET NULL. Who it was is still answered by audit_log,
-- whose actor_email survives the account by design (V1). CASCADE would be
-- wrong here: deleting the person who created an AX Renewal project would
-- delete the project, its plan, its runs and everyone else's comments.
--
-- Five of these columns are NOT NULL today and have to allow null first.
-- Every reader of them already treats the UUID as optional (the run
-- history's and confirmations' display names are left joins); the one inner
-- join, the comment thread's author name, becomes a left join in the same
-- change, so a deleted author's comments stay in the thread.

-- --- NOT NULL "who" columns: allow null, then SET NULL on delete ----------

alter table ax_renewal_project alter column created_by drop not null;
alter table ax_renewal_project drop constraint ax_renewal_project_created_by_fkey,
  add constraint ax_renewal_project_created_by_fkey
    foreign key (created_by) references account (id) on delete set null;

alter table ax_renewal_analysis alter column analyzed_by drop not null;
alter table ax_renewal_analysis drop constraint ax_renewal_analysis_analyzed_by_fkey,
  add constraint ax_renewal_analysis_analyzed_by_fkey
    foreign key (analyzed_by) references account (id) on delete set null;

alter table ax_renewal_confirmation alter column confirmed_by drop not null;
alter table ax_renewal_confirmation drop constraint ax_renewal_confirmation_confirmed_by_fkey,
  add constraint ax_renewal_confirmation_confirmed_by_fkey
    foreign key (confirmed_by) references account (id) on delete set null;

alter table ax_renewal_implementation_run alter column started_by drop not null;
alter table ax_renewal_implementation_run drop constraint ax_renewal_implementation_run_started_by_fkey,
  add constraint ax_renewal_implementation_run_started_by_fkey
    foreign key (started_by) references account (id) on delete set null;

alter table ax_renewal_comment alter column author_id drop not null;
alter table ax_renewal_comment drop constraint ax_renewal_comment_author_id_fkey,
  add constraint ax_renewal_comment_author_id_fkey
    foreign key (author_id) references account (id) on delete set null;

-- --- Already-nullable "who" columns: SET NULL on delete --------------------

alter table ax_renewal_project drop constraint ax_renewal_project_manifest_attached_by_fkey,
  add constraint ax_renewal_project_manifest_attached_by_fkey
    foreign key (manifest_attached_by) references account (id) on delete set null;

alter table ax_renewal_stack_layer drop constraint ax_renewal_stack_layer_updated_by_fkey,
  add constraint ax_renewal_stack_layer_updated_by_fkey
    foreign key (updated_by) references account (id) on delete set null;

alter table ax_renewal_architecture_plan drop constraint ax_renewal_architecture_plan_updated_by_fkey,
  add constraint ax_renewal_architecture_plan_updated_by_fkey
    foreign key (updated_by) references account (id) on delete set null;

alter table ax_renewal_page_mapping drop constraint ax_renewal_page_mapping_updated_by_fkey,
  add constraint ax_renewal_page_mapping_updated_by_fkey
    foreign key (updated_by) references account (id) on delete set null;

alter table ax_renewal_db_change drop constraint ax_renewal_db_change_updated_by_fkey,
  add constraint ax_renewal_db_change_updated_by_fkey
    foreign key (updated_by) references account (id) on delete set null;

alter table ax_renewal_db_connection drop constraint ax_renewal_db_connection_connected_by_fkey,
  add constraint ax_renewal_db_connection_connected_by_fkey
    foreign key (connected_by) references account (id) on delete set null;

alter table ax_renewal_destination drop constraint ax_renewal_destination_updated_by_fkey,
  add constraint ax_renewal_destination_updated_by_fkey
    foreign key (updated_by) references account (id) on delete set null;

alter table ax_renewal_comment drop constraint ax_renewal_comment_deleted_by_fkey,
  add constraint ax_renewal_comment_deleted_by_fkey
    foreign key (deleted_by) references account (id) on delete set null;

alter table claude_usage_member drop constraint claude_usage_member_invited_by_fkey,
  add constraint claude_usage_member_invited_by_fkey
    foreign key (invited_by) references account (id) on delete set null;

alter table docs_content drop constraint docs_content_updated_by_fkey,
  add constraint docs_content_updated_by_fkey
    foreign key (updated_by) references account (id) on delete set null;
