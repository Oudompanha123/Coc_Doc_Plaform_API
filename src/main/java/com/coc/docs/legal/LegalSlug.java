package com.coc.docs.legal;

import java.util.Locale;
import java.util.Optional;

/**
 * Mirrors the {@code legal_page_version.slug} check constraint.
 *
 * <p>Only two legal pages exist today — an enum rather than a lookup table
 * keeps that simple, the same tradeoff {@code VersionStatus} makes for
 * {@code page_version.status}.
 *
 * <p>// req: ADR-003
 */
public enum LegalSlug {
    TERMS,
    PRIVACY;

    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Optional<LegalSlug> parse(String value) {
        if (value == null) {
            return Optional.empty();
        }
        for (LegalSlug s : values()) {
            if (s.wire().equalsIgnoreCase(value)) {
                return Optional.of(s);
            }
        }
        return Optional.empty();
    }
}
