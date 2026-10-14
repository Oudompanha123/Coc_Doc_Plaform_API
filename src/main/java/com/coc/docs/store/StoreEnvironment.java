package com.coc.docs.store;

import java.util.Locale;
import java.util.Optional;

/**
 * Which build of a version a release is. Mirrors
 * {@code store_app_release.environment}'s check constraint (V82).
 *
 * <p>// req: ADR-108
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
