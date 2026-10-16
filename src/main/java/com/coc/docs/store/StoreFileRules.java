package com.coc.docs.store;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * The rules every Store file backend shares — local disk and S3 alike — so a
 * version, a file name or a logo is judged the same wherever it ends up
 * (ADR-108, ADR-111, ADR-115).
 *
 * <p>// req: ADR-108, ADR-111, ADR-115
 */
public final class StoreFileRules {

    private StoreFileRules() {}

    /** Mirrors {@code store_build.version}'s check constraint (V85). */
    static final Pattern VERSION = Pattern.compile("^[0-9A-Za-z]([0-9A-Za-z._+-]{0,62}[0-9A-Za-z])?$");

    static final int MAX_FILE_NAME = 120;

    /** A logo is an icon, not a photo album. */
    static final long MAX_LOGO_BYTES = 2L * 1024 * 1024;

    /**
     * Raster only. SVG is left out on purpose: it is a document that can carry
     * script, and the logo route is public.
     */
    static final Map<String, String> LOGO_TYPES = Map.of(
            "image/png", "png",
            "image/jpeg", "jpg",
            "image/webp", "webp");

    public static boolean isValidVersion(String version) {
        return version != null && VERSION.matcher(version).matches();
    }

    /**
     * The uploader's file name, reduced to something every file system will
     * take as a single name: path parts dropped, anything outside a
     * conservative set replaced, no leading dot, trailing dots and spaces gone
     * (Windows strips them silently, which would make two names one).
     */
    public static String safeFileName(String original) {
        String name = original == null ? "" : original;
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        name = name.substring(slash + 1);
        name = name.replaceAll("[^0-9A-Za-z._()\\- ]", "_").trim();
        name = name.replaceAll("^[.\\s]+", "").replaceAll("[.\\s]+$", "");
        if (name.length() > MAX_FILE_NAME) {
            int dot = name.lastIndexOf('.');
            String extension = dot > 0 && name.length() - dot <= 10 ? name.substring(dot) : "";
            name = name.substring(0, MAX_FILE_NAME - extension.length()) + extension;
        }
        return name.isEmpty() ? "download" : name;
    }

    /** {@code {platform}/{version}/{environment}/{appId}/{fileName}} — the same key on disk and in S3. */
    static String buildKey(StorePlatform platform, String version, StoreEnvironment environment, java.util.UUID appId,
            String fileName) {
        return String.join("/", platform.wire(), version, environment.wire(), appId.toString(), fileName);
    }

    static String logoKey(java.util.UUID appId, String extension) {
        return imageKey(StoreImageKind.LOGO, appId, extension);
    }

    /** {@code logos/{appId}.{ext}} or {@code banners/{appId}.{ext}} (ADR-117). */
    static String imageKey(StoreImageKind kind, java.util.UUID appId, String extension) {
        return kind.folder() + "/" + appId + "." + extension;
    }

    /** Whether the first bytes really are the image type claimed. */
    static boolean matchesMagic(byte[] head, int n, String contentType) {
        return switch (contentType) {
            case "image/png" -> n >= 8
                    && (head[0] & 0xFF) == 0x89 && head[1] == 'P' && head[2] == 'N' && head[3] == 'G';
            case "image/jpeg" -> n >= 3
                    && (head[0] & 0xFF) == 0xFF && (head[1] & 0xFF) == 0xD8 && (head[2] & 0xFF) == 0xFF;
            case "image/webp" -> n >= 12
                    && head[0] == 'R' && head[1] == 'I' && head[2] == 'F' && head[3] == 'F'
                    && head[8] == 'W' && head[9] == 'E' && head[10] == 'B' && head[11] == 'P';
            default -> false;
        };
    }
}
