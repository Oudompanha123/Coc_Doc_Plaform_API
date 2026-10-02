-- Which attendance report has already been mailed to whom (ADR-086).
--
-- WHY THIS EXISTS AT ALL
--
-- The report is now mailed when somebody signs in, so that it reaches their
-- real inbox without anyone configuring a mailbox. Signing in is not a once-a-
-- day event: people sign in from a phone and a laptop, a session expires, a
-- tab is reopened. Without a record of what has been sent, every one of those
-- mails the same report again, and the feature becomes the reason people turn
-- off notifications.
--
-- So the rule is one mail per person per report, and this table is what makes
-- that rule hold -- not a timestamp on the account, and not the sender
-- remembering. It holds across restarts and across two replicas racing,
-- because the primary key is the arbiter.
--
-- WHY THE KEY IS (account, notification) AND NOT (account, date)
--
-- HRMS sends one report per department per day, and somebody may be able to
-- see more than one of them. Keyed by date, the second department's report
-- would look like a duplicate of the first and silently never arrive.
--
-- WHY THE ROWS ARE DISPOSABLE
--
-- Both foreign keys cascade. Deleting an account takes its send history with
-- it; pruning an old notification (HrmsMailPollJob does this on a retention
-- window) takes the record that it was sent. Neither is evidence of anything:
-- audit_log records what this system did, and these rows exist only to stop a
-- second copy going out while the report is still current.
--
--   // req: FR-OPS-001, NFR-SEC-PII, ADR-086

create table if not exists attendance_report_sent (
    account_id      uuid        not null
        references account (id) on delete cascade,

    notification_id bigint      not null
        references hrms_notification (id) on delete cascade,

    sent_at         timestamptz not null default now(),

    primary key (account_id, notification_id)
);

-- "Has this person been sent anything lately", and the per-account cleanup
-- that a cascade does not cover. The primary key already serves the exact
-- lookup; this serves the scan by account.
create index if not exists attendance_report_sent_account_idx
    on attendance_report_sent (account_id, sent_at desc);

comment on table attendance_report_sent is
  'One row per (account, report) that has been mailed out (ADR-086). Exists to '
  'stop a second copy being sent when the same person signs in again -- not as '
  'an audit record, which is what audit_log is for. Disposable: both foreign '
  'keys cascade.';
