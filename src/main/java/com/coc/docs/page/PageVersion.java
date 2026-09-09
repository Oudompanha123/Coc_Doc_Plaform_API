package com.coc.docs.page;

import com.coc.docs.content.Locale;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

/**
 * One locale of one page, in one status.
 *
 * <p>{@code versionNo} is the optimistic lock: an editor sends back the number
 * it loaded, and a mismatch is refused rather than silently overwriting a
 * colleague's work (FR-CONT-013).
 *
 * <p>{@code sourceVersionId} records which published {@code en} version a
 * translation was made from. Staleness is DERIVED from it rather than stored as
 * a mutable flag, so it cannot drift out of agreement with reality (ADR-003).
 *
 * <p>// req: FR-CONT-003, FR-CONT-012, FR-CONT-013, FR-I18N-002
 */
public record PageVersion(
        UUID id,
        UUID pageId,
        Locale locale,
        VersionStatus status,
        String title,
        String description,
        JsonNode body,
        int versionNo,
        UUID sourceVersionId,
        UUID createdBy,
        Instant createdAt,
        Instant updatedAt) {}
