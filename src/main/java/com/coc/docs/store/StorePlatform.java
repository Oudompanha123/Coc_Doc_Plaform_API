package com.coc.docs.store;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * The platforms the Store page offers, and which install files each accepts.
 * Mirrors {@code store_app.platform}'s check constraint (V82).
 *
 * <p>The extension list is what keeps an upload an install file rather than
 * anything at all: the download route sends every file as an attachment, so
 * nothing here is ever rendered, but a Store that offered a {@code .html}
 * under an Android icon would still be wrong.
 *
 * <p>// req: ADR-108
 */
public enum StorePlatform {
    ANDROID(Set.of("apk", "aab")),
    IOS(Set.of("ipa")),
    WINDOWS(Set.of("exe", "msi", "msix", "appx", "zip")),
    MACOS(Set.of("dmg", "pkg", "zip"));

    private final Set<String> extensions;

    StorePlatform(Set<String> extensions) {
        this.extensions = extensions;
    }

    /** As stored and as sent on the wire: lower case. */
    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    public Set<String> extensions() {
        return extensions;
    }

    public boolean accepts(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot >= 0 && extensions.contains(fileName.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    public static Optional<StorePlatform> parse(String value) {
        if (value == null) {
            return Optional.empty();
        }
        for (StorePlatform platform : values()) {
            if (platform.wire().equals(value.trim().toLowerCase(Locale.ROOT))) {
                return Optional.of(platform);
            }
        }
        return Optional.empty();
    }
}
