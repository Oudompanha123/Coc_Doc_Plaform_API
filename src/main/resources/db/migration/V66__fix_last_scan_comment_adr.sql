-- Correct the ADR cited in claude_usage_member.last_scan_at's comment.
--
-- WHY A WHOLE MIGRATION FOR A COMMENT
--
-- V65 was applied to a database while its text still cited ADR-095, and the
-- file was corrected to ADR-097 afterwards. Two things are wrong as a result,
-- and only one of them is fixed by repairing the checksum:
--
--   1. Flyway refuses to start, because V65's stored checksum no longer
--      matches the file. That is the loud one, and `flyway repair` settles it.
--
--   2. The comment already in the database still says ADR-095 -- which is
--      "pending approval on password sign-in", a different decision entirely.
--      Repairing records V65 as applied and that wrong text stays forever.
--
-- So the checksum repair alone would trade a startup failure for a permanent,
-- silent lie in the schema. This migration fixes the second half, and it has
-- to be a new version because V65 is already applied everywhere: editing an
-- applied migration is what caused this.
--
-- The comment matters more than a comment usually would. It is the only thing
-- in the schema that says which decision introduced the column, and someone
-- reading it while tracing ADR-095 would find a column that has nothing to do
-- with what they are looking at.
--
-- Idempotent: `comment on` replaces whatever is there, so this is safe on a
-- database that never saw the wrong text -- a fresh one, where V65 ran from
-- the corrected file and already says ADR-097.
--
--   // req: FR-USAGE-002, ADR-097

comment on column claude_usage_member.last_scan_at is
  'When this member''s reporter last completed a scan (any machine), whether or '
  'not it uploaded usage. Server time. NULL = none recorded yet (ADR-097).';
