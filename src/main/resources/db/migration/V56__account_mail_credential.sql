-- An account's own Gmail credential, so its attendance mail is sent by it
-- rather than by one shared sender (ADR-063).
--
-- WHAT THIS HOLDS, STATED PLAINLY
--
-- app_password_encrypted is a Google App Password, encrypted (AES-256-GCM,
-- SecretBox). An App Password is NOT scoped to sending: it grants full read
-- and send on that mailbox. So this column's blast radius is each supplier's
-- entire Gmail account, not their attendance. ADR-063 §4.2 records that as an
-- accepted cost and names the better answer (OAuth, gmail.send) as the
-- follow-up this should be replaced by.
--
-- Encrypted and not hashed, unlike every other secret in this schema
-- (password_hash, session token hashes, reset and registration codes). A hash
-- cannot authenticate to Gmail. That single difference is why this project now
-- has an encryption key at all, and why ADR-007's "no application-layer column
-- encryption to key" N/A is reopened by this migration.
--
-- A SEPARATE TABLE, NOT COLUMNS ON account
--
-- account is read on every authenticated request (AccountRepository's mapper
-- selects it whole). Putting a credential there would carry it into every
-- session lookup, every admin listing and every response mapper that forgets
-- to exclude it. Here it is reached only by the one service that sends mail,
-- and a SELECT * on account cannot leak it.
--
-- on delete cascade: deleting an account takes its credential with it. There
-- is no archival case for a password to somebody else's mailbox.
--
--   // req: FR-OPS-001, NFR-SEC-PII, NFR-SEC-CHANNEL, ADR-063

create table account_mail_credential (
    account_id uuid primary key
        references account (id) on delete cascade,

    -- The mailbox to authenticate as. Normally the account's own email, but
    -- kept separate: somebody may hold their work mail under another address,
    -- and guessing it from account.email would send as the wrong identity.
    smtp_username text not null,

    -- AES-256-GCM, base64, nonce-prefixed. Never returned by any endpoint --
    -- the API is write-only by design (ADR-063 §2).
    app_password_encrypted text not null,

    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),

    constraint account_mail_credential_username_not_blank
        check (length(btrim(smtp_username)) > 0)
);

comment on table account_mail_credential is
  'An account''s own Gmail App Password (ADR-063), encrypted at rest. Set only '
  'by its owner, never read back over the API, and used solely to send that '
  'person their own attendance mail from their own address.';

comment on column account_mail_credential.app_password_encrypted is
  'AES-256-GCM under app.security.encryption-key. Grants full mailbox read AND '
  'send, not just send -- see ADR-063 section 4.2.';
