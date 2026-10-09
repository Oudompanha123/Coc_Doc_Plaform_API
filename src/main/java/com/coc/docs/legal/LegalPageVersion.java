package com.coc.docs.legal;

import com.coc.docs.page.VersionStatus;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

/**
 * One version of one legal page (Terms of Service or Privacy Policy), in one
 * status.
 *
 * <p>{@code versionNo} is the optimistic lock, same role it plays on {@code
 * page_version} (FR-CONT-013): an editor sends back the number it loaded, and
 * a mismatch is refused rather than silently overwritten.
 *
 * <p>{@code publishedAt}/{@code publishedBy} are null for a draft or an
 * archived row's original insert — they are set once, at the moment a draft
 * is promoted, and never change afterwards (publishing again creates a new
 * row rather than mutating this one).
 */
public record LegalPageVersion(
        UUID id,
        LegalSlug slug,
        VersionStatus status,
        JsonNode content,
        int versionNo,
        Instant publishedAt,
        UUID publishedBy,
        /** Max 200 chars, set only at the moment this row is promoted —
         *  null for a draft, or for any row published before this field
         *  existed. */
        String publishNote,
        Instant createdAt,
        Instant updatedAt) {}
