-- A Google OAuth grant as an alternative to a stored App Password (ADR-085).
--
-- WHY THIS AND NOT A SECOND TABLE
--
-- It answers the same question account_mail_credential already answers -- "how
-- does this account authenticate to its own mailbox" -- and exactly one answer
-- may be in force at a time. Two tables would make "both at once" expressible,
-- and then something has to decide which wins on every send. A kind column
-- makes the exclusivity a fact about the row rather than a rule somebody has
-- to remember.
--
-- WHY BOTH KINDS STILL EXIST
--
-- OAuth needs the Google consent screen configured and, for these scopes,
-- Google's verification before anyone outside a test-user list can grant it
-- (ADR-085 section 4.2). Removing the App Password path before every account
-- has moved would strand whoever cannot complete the flow. The column is what
-- lets the two coexist until the last account migrates, at which point the
-- app_password_encrypted column can be dropped.
--
-- WHAT IS STORED, AND WHAT DELIBERATELY IS NOT
--
-- oauth_refresh_token_encrypted is a long-lived Google refresh token, sealed
-- with the same SecretBox key as app_password_encrypted and for the same
-- reason: it must be replayed to Google, so it cannot be hashed. ADR-007's
-- "no application-layer column encryption to key" stays as false as ADR-063
-- made it.
--
-- ACCESS tokens are NOT stored. They last an hour, they are fetched on demand
-- and held in memory for the length of one send or one poll. A stored access
-- token would be one more secret at rest buying nothing -- the refresh token
-- can always mint another.
--
-- It is a narrower secret than an App Password, which is the point of
-- ADR-085: granted for gmail.send and gmail.readonly only, it cannot delete
-- mail, cannot change account settings, cannot sign in anywhere, and the
-- person who granted it can revoke it from their Google account without
-- touching their password. It still READS the whole mailbox -- see ADR-085
-- section 4.2, which does not pretend otherwise.
--
--   // req: FR-OPS-001, NFR-SEC-PII, NFR-SEC-CHANNEL, ADR-085

-- 'app_password' is every row written before this migration.
create type mail_credential_kind as enum ('app_password', 'oauth');

alter table account_mail_credential
    add column if not exists kind mail_credential_kind not null default 'app_password',
    add column if not exists oauth_refresh_token_encrypted text,
    add column if not exists oauth_scope text,
    add column if not exists oauth_granted_at timestamptz;

-- app_password_encrypted was `not null` when it was the only way to
-- authenticate. An OAuth row has no App Password and must not invent one, so
-- the column becomes nullable and the check below carries what the NOT NULL
-- used to guarantee: whichever kind a row claims, the secret for that kind is
-- present and the other one is absent.
alter table account_mail_credential
    alter column app_password_encrypted drop not null;

alter table account_mail_credential
    add constraint account_mail_credential_kind_matches_secret check (
        (kind = 'app_password'
             and app_password_encrypted is not null
             and oauth_refresh_token_encrypted is null)
        or (kind = 'oauth'
             and oauth_refresh_token_encrypted is not null
             and app_password_encrypted is null)
    );

comment on column account_mail_credential.kind is
  'Which secret authenticates this account to Gmail (ADR-085). Exactly one is '
  'present, enforced by account_mail_credential_kind_matches_secret.';

comment on column account_mail_credential.oauth_refresh_token_encrypted is
  'Google refresh token, AES-256-GCM under app.security.encryption-key. Scoped '
  'to gmail.send and gmail.readonly, revocable by its owner from their Google '
  'account -- unlike an App Password, which grants everything. Access tokens '
  'are never stored: they are minted from this on demand.';

comment on column account_mail_credential.oauth_scope is
  'The scopes Google actually granted, as returned by the token exchange -- not '
  'what was asked for. A person may grant less than was requested, and sending '
  'mail with a read-only grant should fail loudly here rather than at the relay.';
