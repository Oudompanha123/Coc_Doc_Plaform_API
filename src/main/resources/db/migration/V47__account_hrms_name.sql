-- Links a platform account to the person it is in the HRMS attendance mail.
--
-- The mail identifies people by one thing: a display-name string in a table
-- cell ("Nin Srey Nuth"). There is no employee id and no email in it. So for a
-- plain `user` to be shown their own attendance row and nobody else's, the
-- backend needs a key, and every candidate it could infer is wrong:
--
--   - account.display_name is edited freely by its owner and by admins, so a
--     rename would silently change whose attendance somebody sees.
--   - the mail's recipient list carries Gmail local-parts ("ninsreynuth19"),
--     which resemble the names without matching them. A near-miss here shows
--     one employee's clock-in times to a different employee.
--
-- Hence an explicit, deliberately-set column rather than a guess. Null is the
-- normal state and means "not linked": the holder sees an empty report, which
-- is the safe answer and the honest one. Nothing back-fills it.
--
-- Not unique, and not a foreign key to anything. HRMS is somebody else's
-- system and this platform does not get to assert that its names are unique or
-- stable; the value is matched case- and whitespace-insensitively at read time
-- rather than normalised here, so correcting a mapping is an UPDATE and never
-- a migration.
--
--   // req: FR-OPS-001, NFR-SEC-PII, ADR-032, ADR-042

alter table account
  add column hrms_name text;

comment on column account.hrms_name is
  'The exact Name cell this account''s owner appears under in the HRMS '
  'attendance mail. Null means not linked -- the holder sees no attendance '
  'rows rather than somebody else''s. Set by a super admin, never inferred.';
