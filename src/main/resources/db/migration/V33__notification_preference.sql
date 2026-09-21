-- Coc Docs Platform -- real per-account notification preferences (ADR-041)
--
-- Backs the Settings page's "Notifications" toggles, which until now stored
-- nothing and gated nothing (NotificationsCard.tsx's own doc comment said so
-- outright). Opt-out by absence: a missing row means enabled, so this
-- migration changes nobody's current behavior on the day it ships -- a row
-- only needs to exist for a (account, type) pair somebody has actually
-- turned off.
--
-- type is text holding NotificationType.key(), the same convention the
-- notification table itself uses (ADR-034 decision 2, V20) -- adding a
-- notification type is a Java constant, never a migration here.
--
--   // req: ADR-025, ADR-034, ADR-041

create table notification_preference (
  account_id  uuid        not null references account(id) on delete cascade,
  type        text        not null,
  enabled     boolean     not null,
  updated_at  timestamptz not null default now(),
  primary key (account_id, type)
);
