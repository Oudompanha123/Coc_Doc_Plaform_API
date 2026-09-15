package com.coc.docs.notify;

import java.util.Locale;
import java.util.Optional;

/**
 * Where a push destination lives.
 *
 * <p>{@code WEB} is the only one in use. The other two are declared now
 * because a mobile app is expected and the database already constrains the
 * column to these three (V11) — adding a platform later means a migration and
 * a backfill that can only guess, while declaring one costs a line.
 *
 * <p>// req: ADR-025
 */
public enum DevicePlatform {
    WEB,
    ANDROID,
    IOS;

    /** Lower case, matching the {@code device_token.platform} check constraint. */
    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Optional<DevicePlatform> parse(String value) {
        if (value == null) {
            return Optional.empty();
        }
        for (DevicePlatform platform : values()) {
            if (platform.wire().equalsIgnoreCase(value.trim())) {
                return Optional.of(platform);
            }
        }
        return Optional.empty();
    }
}
