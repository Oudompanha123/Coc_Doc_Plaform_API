-- Coc Docs Platform -- self-uploaded profile photos (ADR-026)
--
-- Revises V5__account_avatar.sql: avatar_url may now also hold a URL this
-- system stored itself (object storage behind the S3 API, CONST-TECH-08),
-- not only a URL an identity provider supplied.
--
-- avatar_uploaded_at is what tells the two apart. Non-null means the account
-- owner set this photo on purpose through "My Profile" -- SessionService.
-- refreshProfile checks it on every Google/Keycloak sign-in and must not
-- overwrite it with the identity provider's picture claim while it is set.
-- Null is the original meaning restored: nothing owner-controlled is in
-- place, so the identity provider's picture (or initials, absent one) shows
-- as before this migration existed.
--
--   // req: FR-AUTH-005, NFR-SEC-PII, ADR-026

alter table account add column avatar_uploaded_at timestamptz;
