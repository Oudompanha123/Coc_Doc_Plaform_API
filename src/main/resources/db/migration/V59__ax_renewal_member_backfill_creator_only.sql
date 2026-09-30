-- Coc Docs Platform -- AX Renewal: members are the creator and whoever they invite (ADR-064)
--
-- WHY
-- V58 backfilled ax_renewal_project_member with everyone who had ever acted
-- on a project (every *_by column), on the ADR-063 reasoning that membership
-- only gated changes and reading stayed open. ADR-064 makes membership gate
-- reading too: a project is visible only to its creator, the people
-- explicitly added to it, and a super admin. Under that rule the V58
-- contributor rows are wrong -- nobody invited those accounts, and each one
-- would keep a project visible to someone its team never chose to show it
-- to. V58 itself is left as applied (Flyway checksums); this corrects its
-- result.
--
-- WHAT IS REMOVED, EXACTLY
-- Only the rows V58's backfill inserted for someone other than the project's
-- creator. Those rows are identifiable precisely: V58 wrote them with
-- added_by = null, all in one transaction, so all with one identical
-- added_at (now() is fixed per transaction) -- the earliest added_at of any
-- null-added_by row. A later, explicit add always records its adder, and
-- even if that adder's account is since deleted (added_by -> null, V58's
-- on delete set null) its added_at is a different, later instant, so it is
-- not touched. The creator rows V58 wrote are kept.
--
-- On a database where V58 found no projects, this deletes nothing.
--
-- NOT REVERSIBLE BY ITSELF: a removed contributor who should keep access
-- must be added back explicitly under Workspace -> Members.
--
--   // req: ADR-064

delete from ax_renewal_project_member m
using ax_renewal_project p
where p.id = m.project_id
  and m.added_by is null
  and m.account_id <> p.created_by
  and m.added_at = (select min(added_at) from ax_renewal_project_member where added_by is null);

-- Every project keeps its creator as a member (the rule ADR-064 now states,
-- and AxRenewalService#removeMember now enforces). V58 already inserted
-- these; this only guarantees it for any row that went missing since.
insert into ax_renewal_project_member (project_id, account_id)
select id, created_by from ax_renewal_project
on conflict do nothing;
