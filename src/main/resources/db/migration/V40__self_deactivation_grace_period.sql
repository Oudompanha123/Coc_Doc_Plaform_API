-- Coc Docs Platform -- self-service deactivation gets a grace period
-- (revision of ADR-042)
--
-- ADR-042 originally deactivated the caller's own account immediately.
-- This revision adds a chosen delay instead: deactivation_scheduled_for
-- holds when a pending self-deactivation will actually flip
-- deactivated_at. Null means no self-deactivation is pending.
--
-- Signing back in before that time cancels it (SessionService.issue, via
-- AccountRepository.cancelScheduledDeactivation) -- every session was
-- already revoked when the deactivation was scheduled, so a later sign-in
-- proves it is the account's own owner, not a stale cookie. A scheduled
-- sweep (ScheduledDeactivationSweeper) flips deactivated_at once the time
-- passes without a sign-in.
--
--   // req: FR-AUTH-003, FR-AUTH-007, ADR-042

alter table account
  add column deactivation_scheduled_for timestamptz;

-- Backs the sweep's "what's due" query. Partial because this column is
-- null for nearly every row -- only accounts with a pending self-service
-- deactivation ever set it.
create index idx_account_deactivation_scheduled_for
  on account (deactivation_scheduled_for)
  where deactivation_scheduled_for is not null;
