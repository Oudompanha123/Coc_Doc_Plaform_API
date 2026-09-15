-- Coc Docs Platform — make an account row actually deletable
-- Implements ADR-023: a super_admin can remove an account outright, not only
-- deactivate it.                          // req: FR-AUTH-003, FR-AUTH-008, ADR-023

-- Every other reference to account(id) already says what should happen when
-- the row goes, and the schema was clearly written with deletion in mind:
--
--   app_session.account_id           cascade    -- the session dies with it
--   password_reset_token.account_id  cascade    -- so does an unspent token
--   account_role.account_id          cascade    -- and the role grants
--   audit_log.actor_id               set null   -- the entry stays, see below
--   page_version.created_by          set null   -- the content stays
--
-- audit_log also keeps actor_email as a plain text column, with the comment
-- "denormalised: survives account deletion" — written in V1, before anything
-- could delete an account. The trail answers "who did this" from that column
-- whether or not the account still exists.
--
-- account_role.granted_by was the one exception: declared with no ON DELETE
-- action at all, so PostgreSQL defaults it to NO ACTION and the delete is
-- refused:
--
--   update or delete on table "account" violates foreign key constraint
--   "account_role_granted_by_fkey" on table "account_role"
--
-- That is not a decision anyone recorded, it is an omission — and it bites
-- exactly the accounts an admin most wants to remove, because granting a role
-- is something only a super_admin does. Anyone who ever approved a colleague
-- became undeletable.
--
-- set null, matching audit_log.actor_id and the column's own V4 comment, which
-- already describes null as the normal value for a grant with no human actor.
-- The grant survives; only the attribution is lost, and audit_log holds that
-- attribution anyway with an email that does not disappear.
alter table account_role drop constraint if exists account_role_granted_by_fkey;

alter table account_role
  add constraint account_role_granted_by_fkey
  foreign key (granted_by) references account (id) on delete set null;

comment on column account_role.granted_by is
  'Who granted this role. Null for V4 backfills, for a role a sign-in provisions itself, and now for a grant whose granter has been deleted (ADR-023) — audit_log.actor_email keeps the attribution in every case.';
