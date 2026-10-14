-- Coc Docs Platform -- Store: a removed build keeps its row (ADR-108)
--
-- WHY
-- Deleting a build used to delete its row, so the version history forgot it
-- had ever existed. An app's page now lists every version with what became
-- of each build -- downloadable, "Removed", or never uploaded (N/A) -- so a
-- reader can tell "1.0.2 was withdrawn" from "there was no 1.0.2".
--
-- Removing a build now deletes its file and stamps removed_at; the row stays.
-- A removed build is never downloadable and never counts as "latest".
--
-- The one-build-per-(app, version, environment) rule now applies to live
-- builds only: a version whose build was removed can be uploaded again (a
-- fixed re-release), and the history then shows the live one. The plain
-- unique constraint from V82 is replaced by a partial unique index.
--
-- V82 is not edited: it is already on dev and applied on teammates'
-- databases, and Flyway refuses an applied migration whose content changed.
--
-- // req: ADR-108

alter table store_app_release
  add column removed_at timestamptz,
  add column removed_by uuid references account(id) on delete set null;

alter table store_app_release drop constraint store_app_release_app_id_version_environment_key;

create unique index store_app_release_live_key
  on store_app_release (app_id, version, environment)
  where removed_at is null;

comment on column store_app_release.removed_at is
  'When an admin removed this build. Its file is deleted; the row stays so the version history shows "Removed" (ADR-108). NULL = live.';
