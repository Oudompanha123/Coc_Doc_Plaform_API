package com.coc.docs.axrenewal;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

/**
 * A row of {@code ax_renewal_db_connection} — metadata about the legacy
 * database this project's schema plan reads, deliberately carrying no
 * credential column (ADR-037). Phase 1 does not open a live connection to a
 * third-party database.
 */
public record AxRenewalDbConnection(
        UUID projectId,
        String engine,
        String host,
        Integer port,
        String databaseName,
        String schemaName,
        JsonNode schemaSnapshot,
        Instant connectedAt,
        UUID connectedBy) {}
