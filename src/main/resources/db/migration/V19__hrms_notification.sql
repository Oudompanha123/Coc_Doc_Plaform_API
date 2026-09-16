-- Coc Docs Platform -- knowing that an HRMS report arrived
--
-- WHY
-- HRMS KOSIGN mails an attendance report to people's personal inboxes each
-- morning. It is a third-party system at wecambodia.com: it will not call this
-- API, and this backend has no public URL for it to call anyway while hosting
-- is [DEFERRED] (ADR-012 decision 2). So the platform goes and looks, over
-- IMAP, and records that the mail arrived.
--
--   // req: FR-OPS-001, NFR-SEC-PII, NFR-OPS-AUDIT
--
-- WHY A TABLE OF ITS OWN AND NOT audit_log
-- NotificationFeedService derives the bell feed from audit_log, so putting
-- these rows there would have cost no schema change and no new query. Two
-- reasons not to:
--
--   1. audit_log records what THIS system did, with an actor account behind
--      each row. Nobody here did anything -- a different company's server sent
--      an email. A row claiming otherwise makes the audit trail answer "who
--      did this" with a lie.
--   2. audit_log has `revoke update, delete ... from public` and is retained
--      >= 1 year for compliance. A mistake in a mail parser would be permanent
--      and unfixable. These rows are disposable by design, and this table lets
--      them be disposed of.
--
-- WHAT IS DELIBERATELY NOT STORED HERE
-- The report body. The mail names ~60 colleagues with their check-in and
-- check-out times, who was late and who missed a check-out. That is HR data
-- about people who never agreed to it being in a documentation CMS, and
-- NFR-SEC-PII confines identity data in this system to super_admin. Storing it
-- would be a genuine expansion of what this database holds, needing an ADR and
-- HR's agreement -- not a side effect of wanting a bell icon to light up.
--
-- So: a subject line, a date, and when it landed. Enough to say "it arrived,
-- go read it in Gmail" and nothing more. If this table is ever widened, that
-- is the decision being made, and it should be made on purpose.

create table if not exists hrms_notification (
  id          bigserial   primary key,

  -- RFC 5322 Message-ID, the dedupe key. The poller has no memory of its own:
  -- it re-reads a window of the mailbox on every run, and this constraint --
  -- not the poller's bookkeeping -- is what stops the same report appearing
  -- twice. A unique index is honest about that in a way a "last seen" column
  -- in some other table would not be, because it holds even if the poller is
  -- restarted, run twice, or run from two replicas at once.
  message_id  text        not null,

  -- The subject exactly as it arrived, e.g. 'Attendance on 08-Sep-2026'. Shown
  -- verbatim in the feed. Not parsed for anything except report_date below,
  -- and never trusted as HTML -- it reaches the client as a JSON string.
  subject     text        not null,

  -- The day the report covers, read out of the subject. Nullable on purpose:
  -- HRMS may reword its subject at any time without telling anyone, and a
  -- parser that cannot read the new wording must degrade to "a report
  -- arrived", never to dropping the notification or to guessing a date. A
  -- column full of nulls is also the signal that the wording changed.
  report_date date,

  -- When the mail was sent, from its own Date header, not when this row was
  -- written. The two differ by however long the poller took to notice, and the
  -- feed should show the former.
  received_at timestamptz not null,

  recorded_at timestamptz not null default now()
);

-- Dedupe, and the only uniqueness this table has. Message-IDs are globally
-- unique by construction, so this needs no account or date in the key.
create unique index if not exists hrms_notification_message_id_key
  on hrms_notification (message_id);

-- The feed's only query: newest first, small limit.
create index if not exists hrms_notification_received_idx
  on hrms_notification (received_at desc);

comment on table hrms_notification is
  'That an HRMS report email arrived -- subject, date and arrival time, never the body. Disposable: unlike audit_log these rows may be deleted, and HrmsMailPollJob prunes them on a retention window. See V19 for why the body is not here.';

comment on column hrms_notification.message_id is
  'RFC 5322 Message-ID of the source email. The dedupe key: the poller re-reads a mailbox window each run and relies on this constraint rather than on remembering where it stopped.';

comment on column hrms_notification.report_date is
  'The day the report covers, parsed from the subject. Null when the subject did not match the expected wording, which is a degraded but valid state -- the notification is still shown.';
