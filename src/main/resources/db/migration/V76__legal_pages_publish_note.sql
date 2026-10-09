-- Coc Docs Platform -- Legal pages: optional publish note

-- WHY
-- Task spec §G (pre-publish check): "Optional Publish note (max 200), e.g.,
-- 'Updated data retention section'." shown against each entry in the
-- History panel (§H: "date, publisher, publish note"). Nullable and never
-- backfilled -- every existing published/archived row simply has no note,
-- which is the correct, honest state for a field that didn't exist when
-- they were published.
--
-- // req: ADR-003

alter table legal_page_version add column publish_note text;

alter table legal_page_version
  add constraint legal_page_version_publish_note_length check (char_length(publish_note) <= 200);
