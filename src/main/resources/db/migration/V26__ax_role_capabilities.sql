-- Coc Docs Platform -- AX Role: extend the capability catalog to tech_lead and reviewer
--
-- WHY
-- V24 seeded exactly one capability, `developer`, and its own comment named
-- the follow-up this migration answers: a capability-to-permission mapping
-- layer "is deferred until a second capability actually needs one." The PM
-- asked for the account details screen to offer an "AX Role" of Tech Lead,
-- Developer or Reviewer -- the same three-role model (lead / developer /
-- reviewer) the AX Renewal prototype this feature was integrated from
-- originally had, before it was flattened to just `developer` (ADR-039).
--
-- WHAT THIS IS, AND IS NOT
-- Two more rows in the existing `capability` catalog, nothing else. No new
-- table, no new enum type, no change to `app_role` or to which routes
-- `SecurityConfig` admits -- see ADR-039 for what is deliberately left
-- unchanged and what is left as a follow-up PM decision.
--
--   // req: ADR-038, ADR-039

insert into capability (key, description) values
  ('tech_lead', 'AX Role: leads an AX Renewal effort. Grants no additional route access beyond the existing developer-gated rules until a follow-up decision (ADR-039) wires it in.'),
  ('reviewer', 'AX Role: reviews an AX Renewal plan. Grants no additional route access beyond the existing developer-gated rules until a follow-up decision (ADR-039) wires it in.');
