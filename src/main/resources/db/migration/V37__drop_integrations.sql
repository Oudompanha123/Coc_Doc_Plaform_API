-- Coc Docs Platform -- remove the outgoing-webhook integrations feature (ADR-037, now REVERTED)
--
-- WHY
-- ADR-037's feature (connect/test/rotate/disconnect an outgoing webhook,
-- AES-256-GCM signing secret at rest, async event dispatch) was removed
-- end to end -- frontend page/components, backend controller/service/
-- repository/encryption primitive -- on direct developer instruction. See
-- ADR-037's own Status section for the reversal record.
--
-- V23 (the table's own creation migration) is kept, not deleted or edited --
-- twelve later migrations (V24-V35) already ran after it in any environment
-- that has migrated this far, so rewriting V23 would break Flyway's applied-
-- migration checksum history there. A forward drop is the correct undo, the
-- same "migrations only move forward" discipline every other schema change
-- in this codebase already follows.
--
-- integration_delivery first: it has the foreign key onto integration.

drop table if exists integration_delivery;
drop table if exists integration;
