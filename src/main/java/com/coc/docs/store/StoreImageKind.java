package com.coc.docs.store;

/**
 * The two pictures an app can have (ADR-108, ADR-117): its square icon and
 * its wide banner for the Store page's featured slot. Both are stored, checked
 * and served the same way; they differ only in folder and size cap.
 *
 * <p>// req: ADR-108, ADR-117
 */
public enum StoreImageKind {
    /** {@code logos/{appId}.{ext}}, 2 MB — an icon, not a photo album. */
    LOGO("logo", "logos", 2L * 1024 * 1024),
    /** {@code banners/{appId}.{ext}}, 25 MB — a wide, high-resolution picture (product owner, 2026-10-16; was 5 MB). */
    BANNER("banner", "banners", 25L * 1024 * 1024);

    private final String noun;
    private final String folder;
    private final long maxBytes;

    StoreImageKind(String noun, String folder, long maxBytes) {
        this.noun = noun;
        this.folder = folder;
        this.maxBytes = maxBytes;
    }

    /** "logo" or "banner", for error messages. */
    public String noun() {
        return noun;
    }

    String folder() {
        return folder;
    }

    public long maxBytes() {
        return maxBytes;
    }

    /** "2 MB" / "25 MB". */
    String maxLabel() {
        return (maxBytes / (1024 * 1024)) + " MB";
    }
}
