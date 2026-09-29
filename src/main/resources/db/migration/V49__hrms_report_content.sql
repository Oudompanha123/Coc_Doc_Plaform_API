-- The parsed body of an HRMS report, stored (ADR-057, revising ADR-042).
--
-- WHAT THIS WIDENS, AND WHY IT IS A SEPARATE TABLE
--
-- V21__hrms_notification.sql stores that a report arrived and deliberately
-- nothing else, and says so in its own words:
--
--   "Storing it would be a genuine expansion of what this database holds,
--    needing an ADR and HR's agreement -- not a side effect of wanting a bell
--    icon to light up. If this table is ever widened, that is the decision
--    being made, and it should be made on purpose."
--
-- This is that decision, made on purpose. ADR-042 considered and rejected it;
-- ADR-057 revises that on one new fact: V47's account.hrms_name plus
-- HrmsNotificationService.reportFor now narrow a report to the caller's own
-- row for every role below admin, which closes the "any signed-in user can
-- read ~60 colleagues' attendance" cost ADR-042 accepted as its worst.
--
-- V21's own table is left exactly as it was. The body lives here instead, so
-- that the widening is a visible object rather than extra columns on a table
-- whose comment says it holds none.
--
-- **HR's agreement, which V21 names, is a separate thing from this migration
-- and is not granted by it.** ADR-057 §4.3 carries it as an open condition.
--
-- ON DELETE CASCADE is the retention mechanism, not a convenience: the
-- existing prune deletes hrms_notification rows past app.hrms.retention
-- (90 days by default), and this table goes with them. There is deliberately
-- no second retention setting to keep in step with the first.
--
-- rows/details are jsonb rather than two more tables. Nothing queries inside
-- them -- a report is read whole, by id, and scoped in Java -- so relational
-- columns would buy nothing and would make every person's attendance
-- independently queryable, which is more surface than this feature needs.
--
--   // req: FR-OPS-001, NFR-SEC-PII, ADR-057

create table hrms_report_content (
    notification_id bigint primary key
        references hrms_notification (id) on delete cascade,

    -- The mail's own heading: a department for an attendance report,
    -- "Leave Request" and similar for the rest. Blank when it had none.
    department text not null default '',

    -- One entry per person, in the order the mail listed them. Each is
    -- {name, yesterdayCheckOut:{scheduled,actual,status},
    --        todayCheckIn:{scheduled,actual,status}} -- see HrmsMailContent.Row.
    rows jsonb not null default '[]',

    -- Label/value pairs from the mails that are not attendance tables.
    details jsonb not null default '[]',

    -- The sender's footer.
    note text not null default '',

    -- When this body was parsed and stored, which is not when the mail
    -- arrived (hrms_notification.received_at) and not when the row was
    -- recorded (recorded_at). A re-parse after a parser fix moves this.
    parsed_at timestamptz not null default now()
);

comment on table hrms_report_content is
  'The parsed body of an HRMS report (ADR-057). Holds other people''s '
  'attendance. Never logged, never copied into audit_log, and deleted with '
  'its hrms_notification row when that passes app.hrms.retention.';
