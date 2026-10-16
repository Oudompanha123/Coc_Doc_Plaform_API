package com.coc.docs.store;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Whether an app is a Mobile app or a PC app — chosen on Add App (ADR-112).
 * Mirrors {@code store_app.category}'s check constraint (V86).
 *
 * <p>It decides which platforms the app's builds may be for: an upload whose
 * file is for another platform is refused.
 *
 * <p>// req: ADR-112
 */
public enum StoreCategory {
    MOBILE(List.of(StorePlatform.IOS, StorePlatform.ANDROID)),
    PC(List.of(StorePlatform.WINDOWS, StorePlatform.MACOS));

    private final List<StorePlatform> platforms;

    StoreCategory(List<StorePlatform> platforms) {
        this.platforms = platforms;
    }

    /** As stored and as sent on the wire: lower case. */
    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** "Mobile", "PC" — for messages. */
    public String label() {
        return this == MOBILE ? "Mobile" : "PC";
    }

    public List<StorePlatform> platforms() {
        return platforms;
    }

    public boolean includes(StorePlatform platform) {
        return platforms.contains(platform);
    }

    /** The files this category's builds may be: ".apk, .aab, .ipa" and so on, for an error message. */
    public String extensions() {
        return platforms.stream()
                .flatMap(p -> p.extensions().stream().sorted())
                .map(e -> "." + e)
                .reduce((a, b) -> a + ", " + b)
                .orElse("");
    }

    public static Optional<StoreCategory> parse(String value) {
        if (value == null) {
            return Optional.empty();
        }
        for (StoreCategory category : values()) {
            if (category.wire().equals(value.trim().toLowerCase(Locale.ROOT))) {
                return Optional.of(category);
            }
        }
        return Optional.empty();
    }
}
