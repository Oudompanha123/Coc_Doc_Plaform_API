-- Coc Docs Platform — a real task list for the Overview landing page
-- Implements ADR-029. Deliberately thin: no assignee, no product/target
-- reference, no priority — nothing here that the landing page's "Your
-- Tasks" panel doesn't actually read or write yet.
--                                                          // req: ADR-029

create table task (
  id           uuid        primary key default gen_random_uuid(),

  title        text        not null,

  -- Optional: a task with no due date is still a real task, just not a
  -- time-boxed one.
  due_date     date,

  status       text        not null default 'open'
                            check (status in ('open', 'done')),

  created_by   uuid        references account(id) on delete set null,
  created_at   timestamptz not null default now(),

  -- Set when status transitions to 'done'. Kept alongside status rather
  -- than replacing it, same reasoning as password_reset_token.used_at:
  -- answers "when", not just "whether".
  completed_at timestamptz
);

comment on table task is
  'A minimal, real task list backing the Overview landing page''s "Your Tasks" panel (ADR-029) — not a project-management system, no assignee or priority until a real requirement asks for one.';

-- The landing page's one read: open tasks, soonest due date first, then
-- oldest-created first for tasks with no due date.
create index task_open_due_date_idx on task (status, due_date, created_at);
