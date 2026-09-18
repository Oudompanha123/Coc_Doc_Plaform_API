package com.coc.docs.axrenewal;

import java.util.Optional;

/**
 * One of the four plan sections that must each be explicitly confirmed
 * before an implementation can start (ADR-037).
 */
public enum AxRenewalSection {
    STACK_MOVE("stack_move"),
    ARCHITECTURE("architecture"),
    PAGES("pages"),
    DATABASE("database");

    private final String key;

    AxRenewalSection(String key) {
        this.key = key;
    }

    /** The database and wire form, e.g. {@code stack_move}. */
    public String wire() {
        return key;
    }

    public static Optional<AxRenewalSection> parse(String value) {
        if (value == null) {
            return Optional.empty();
        }
        for (AxRenewalSection s : values()) {
            if (s.key.equalsIgnoreCase(value)) {
                return Optional.of(s);
            }
        }
        return Optional.empty();
    }
}
