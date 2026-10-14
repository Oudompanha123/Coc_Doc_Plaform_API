package com.coc.docs.store;

import java.util.Locale;
import java.util.Optional;

/**
 * Which server a build is for — the "Server Type" of the Next version form.
 * Mirrors {@code store_build.environment}'s check constraint (V85).
 *
 * <p>// req: ADR-111
 */
public enum StoreEnvironment {
    PRODUCTION,
    DEVELOPMENT;

    /** As stored, as sent on the wire, and as the folder name on disk: lower case. */
    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Optional<StoreEnvironment> parse(String value) {
        if (value == null) {
            return Optional.empty();
        }
        for (StoreEnvironment environment : values()) {
            if (environment.wire().equals(value.trim().toLowerCase(Locale.ROOT))) {
                return Optional.of(environment);
            }
        }
        return Optional.empty();
    }
}
