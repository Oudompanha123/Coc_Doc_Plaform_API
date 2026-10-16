package com.coc.docs.store;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * The platforms a Store build can be for, and the install files each takes.
 * Mirrors {@code store_build.platform}'s check constraint (V85).
 *
 * <p>An app is not tied to a platform (ADR-111): an uploaded file's type says
 * which platform it is for — {@link #ofFile}. The extension sets do not
 * overlap, so a file name names at most one platform. A {@code .zip} is not
 * accepted: it would fit Windows and macOS alike.
 *
 * <p>// req: ADR-108, ADR-111
 */
public enum StorePlatform {
    ANDROID(Set.of("apk", "aab")),
    IOS(Set.of("ipa")),
    WINDOWS(Set.of("exe", "msi", "msix", "appx")),
    MACOS(Set.of("dmg", "pkg"));

    private final Set<String> extensions;

    StorePlatform(Set<String> extensions) {
        this.extensions = extensions;
    }

    /** As stored, as sent on the wire, and as the folder name on disk: lower case. */
    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    public Set<String> extensions() {
        return extensions;
    }

    /** The platform an install file is for, from its extension; empty for any other file. */
    public static Optional<StorePlatform> ofFile(String fileName) {
        if (fileName == null) {
            return Optional.empty();
        }
        int dot = fileName.lastIndexOf('.');
        if (dot < 0) {
            return Optional.empty();
        }
        String extension = fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
        return Arrays.stream(values()).filter(p -> p.extensions.contains(extension)).findFirst();
    }

    /** Every accepted extension, for an error message: ".aab, .apk, .appx, ...". */
    public static String allExtensions() {
        return Arrays.stream(values())
                .flatMap(p -> p.extensions.stream())
                .sorted()
                .map(e -> "." + e)
                .reduce((a, b) -> a + ", " + b)
                .orElse("");
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
