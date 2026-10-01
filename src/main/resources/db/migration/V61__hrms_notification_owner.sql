-- Whose inbox an HRMS report came out of (ADR-075).
--
-- WHY THIS EXISTS
--
-- V21 polled one mailbox, so "which mailbox" was not a question worth asking.
-- It is now: an account that has stored its own credential (ADR-063) has its
-- own inbox read, and what is found there belongs to that account and to
-- nobody else.
--
-- WHY THE UNIQUE INDEX HAS TO CHANGE, AND WHY THIS IS NOT OPTIONAL
--
-- V21 made message_id globally unique, reasoning that "Message-IDs are
-- globally unique by construction, so this needs no account in the key". That
-- is true of the ID and false of the row. One email addressed to several
-- people is ONE message with ONE Message-ID, delivered to many mailboxes. Poll
-- two of those mailboxes and the second insert hits the unique index and is
-- discarded by `on conflict do nothing` -- silently, since that clause cannot
-- tell "already recorded" from "somebody else recorded it first".
--
-- The visible symptom would be: whichever account the scheduler happened to
-- poll first gets the report, everyone else's bell stays dark, and it changes
-- between runs. So the key becomes (owner_account_id, message_id). Per-owner
-- dedupe still does the job V21 wanted -- a poller re-reading its own window
-- cannot record its own message twice.
--
-- NULLS NOT DISTINCT (PostgreSQL 15+, CONST-TECH-08) because the shared
-- mailbox's rows carry a null owner. Under the default NULLS DISTINCT every
-- (null, message_id) pair is unique to itself, which would remove dedupe from
-- exactly the inbox that has had it since V21.
--
-- WHY NULL MEANS "THE SHARED MAILBOX" AND NOT A SENTINEL ACCOUNT
--
-- Every row written before this migration came from app.hrms.username, which
-- is a configured address and not an account in this database. Null says that
-- truthfully. A sentinel row in `account` would have to be excluded from every
-- listing, every mail target and every role check for the rest of the
-- project's life.
--
-- on delete cascade: deleting an account removes the reports taken from its
-- mailbox. They are that person's mail, not the organisation's records, and
-- audit_log already holds whatever this system did with them.
--
--   // req: FR-OPS-001, NFR-SEC-PII, NFR-OPS-AUDIT, ADR-075

alter table hrms_notification
    add column if not exists owner_account_id uuid
        references account (id) on delete cascade;

-- The dedupe key V21 established, now carrying the owner. Dropped and rebuilt
-- rather than altered: an index's column list cannot be changed in place, and
-- doing it in one migration keeps the table from ever being without dedupe.
drop index if exists hrms_notification_message_id_key;

create unique index if not exists hrms_notification_owner_message_key
    on hrms_notification (owner_account_id, message_id) nulls not distinct;

-- The feed reads "this account's rows, newest first" on every poll and every
-- page view. Without this it is a sequential scan past everybody else's.
create index if not exists hrms_notification_owner_received_idx
    on hrms_notification (owner_account_id, received_at desc);

comment on column hrms_notification.owner_account_id is
  'The account whose own inbox this was read from (ADR-075). Null means the '
  'shared app.hrms.* mailbox, which is every row written before V61. A row '
  'with an owner is readable ONLY by that owner -- super_admin included -- '
  'because it was taken from that person''s private mail.';
