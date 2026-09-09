package com.coc.docs.content;

import java.util.Optional;

/**
 * The three content locales, mirroring the {@code locale} Postgres enum in
 * {@code V1__initial_schema.sql} and {@code Locale} in
 * {@code frontend/lib/content/blocks.ts}.
 *
 * <p>English is the reference language: translation staleness is measured
 * against the published {@code en} version (ADR-003 §5).
 *
 * <p>Deliberately not {@code java.util.Locale} — the set is closed, and a type
 * that can represent {@code fr-CA} would let an unsupported locale reach the
 * database, where the enum would reject it as a 500 rather than a 400.
 *
 * <p>// req: NFR-I18N-01, FR-I18N-002
 */
public enum Locale {
    EN,
    KM,
    KO;

    /** The wire and database form: lower case. */
    public String wire() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * Parses a wire value. Returns empty rather than throwing so callers can
     * answer with 400 instead of leaking an enum name into an error body.
     */
    public static Optional<Locale> parse(String value) {
        if (value == null) {
            return Optional.empty();
        }
        for (Locale l : values()) {
            if (l.wire().equalsIgnoreCase(value)) {
                return Optional.of(l);
            }
        }
        return Optional.empty();
    }
}
