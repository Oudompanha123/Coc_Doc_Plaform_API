package com.coc.docs.page;

import java.util.Locale;
import java.util.Optional;

/**
 * Mirrors the {@code version_status} Postgres enum.
 *
 * <p>Draft and published coexist as rows, not as a flag on one row, so saving a
 * draft cannot affect what readers see and publishing is a status transition
 * rather than an overwrite (ADR-003, AMB-001).
 *
 * <p>// req: FR-CONT-003, FR-CONT-005, FR-CONT-012
 */
public enum VersionStatus {
    DRAFT,
    PUBLISHED,
    ARCHIVED;

    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Optional<VersionStatus> parse(String value) {
        if (value == null) {
            return Optional.empty();
        }
        for (VersionStatus s : values()) {
            if (s.wire().equalsIgnoreCase(value)) {
                return Optional.of(s);
            }
        }
        return Optional.empty();
    }
}
