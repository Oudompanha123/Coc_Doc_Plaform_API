-- Coc Docs Platform — admin-invited account, temporary password + forced
-- first-login change (revision of ADR-017: a super_admin invite can now
-- issue a password credential instead of a Google-only row).
--                                       // req: FR-AUTH-002, FR-AUTH-003, ADR-014, ADR-017

-- True only for the window between a super_admin's invite and that person's
-- first successful password change. Independent of `status`/`deactivated_at`:
-- this is not about whether the account may sign in, it is about whether the
-- credential it just signed in with may still be used for anything else.
alter table account add column must_change_password boolean not null default false;

comment on column account.must_change_password is
  'True from the moment a super_admin-issued temporary password is set until the account owner replaces it (POST /api/v1/auth/change-password). Cleared by any successful password write to this row, whichever path performs it.';
