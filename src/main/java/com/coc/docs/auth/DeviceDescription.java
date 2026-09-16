package com.coc.docs.auth;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Turns a user agent into the two things a person needs to recognise a device.
 *
 * <p>A description to read — "Chrome on Windows" — and a key to compare, which
 * here is the same string. They are separate methods anyway, because they
 * answer different questions and only one of them may ever change: the wording
 * is free to improve, the key is not, since every stored row was written with
 * the old one and a changed key makes every known device look new.
 *
 * <p><b>Browser and platform, deliberately no version.</b> A version would make
 * every Chrome update a device nobody recognises, and a sign-in alert that
 * fires on a browser update is one people learn to dismiss without reading —
 * at which point it no longer protects anybody. The cost is stated plainly in
 * ADR-027: two machines running the same browser on the same operating system
 * share a key, so a sign-in from an identical stack is not announced. The
 * session list and the audit log remain the record; this is what gets somebody
 * to look at them.
 *
 * <p>Lives in the backend rather than beside the list that renders it, because
 * the push notification is composed here and the two must say the same thing.
 * The frontend previously had its own copy of these rules.
 *
 * <p>// req: FR-AUTH-007, NFR-SEC-SESSION, ADR-027
 */
public final class DeviceDescription {

    /**
     * Stored for a session whose request carried no user agent — a scripted
     * client, or a browser behind something that strips it.
     *
     * <p>A constant rather than null so that these sessions recognise one
     * another. Null would make every one of them unmatched, and an account
     * signed in from such a client would be told it had a new device on every
     * single sign-in.
     */
    public static final String UNKNOWN_KEY = "unknown device";

    private DeviceDescription() {}

    /**
     * Order matters and is not alphabetical. Chrome's user agent contains
     * "Safari", Edge's contains both, and Opera's contains all three, so the
     * most specific claim has to be tested first or everything reports as
     * Safari.
     */
    private static final Pattern EDGE = Pattern.compile("Edg/");
    private static final Pattern OPERA = Pattern.compile("OPR/|Opera");
    private static final Pattern CHROME = Pattern.compile("Chrome/");
    private static final Pattern FIREFOX = Pattern.compile("Firefox/");
    private static final Pattern SAFARI = Pattern.compile("Safari/");

    private static final Pattern WINDOWS = Pattern.compile("Windows");
    private static final Pattern ANDROID = Pattern.compile("Android");
    private static final Pattern IOS = Pattern.compile("iPhone|iPad");
    private static final Pattern MAC = Pattern.compile("Mac OS X|Macintosh");
    private static final Pattern LINUX = Pattern.compile("Linux");

    /** What to show a person deciding whether they recognise this session. */
    public static String describe(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return "Unknown device";
        }

        String browser = browser(userAgent);
        String platform = platform(userAgent);

        if (browser != null && platform != null) {
            return browser + " on " + platform;
        }
        if (browser != null) {
            return browser;
        }
        if (platform != null) {
            return platform;
        }
        // The raw value rather than "Unknown device": a string somebody can
        // search for helps them decide; a shrug does not. Trimmed because this
        // goes in a list row and a full user agent is a paragraph.
        return userAgent.length() <= 40 ? userAgent : userAgent.substring(0, 40);
    }

    /**
     * What to compare against earlier sessions.
     *
     * <p>Case-folded so that a device is not made new by a header the client
     * happened to capitalise differently, and never null, so that the absence
     * of a user agent is itself something two sessions can have in common.
     */
    public static String key(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return UNKNOWN_KEY;
        }
        return describe(userAgent).toLowerCase(Locale.ROOT);
    }

    private static String browser(String userAgent) {
        if (EDGE.matcher(userAgent).find()) return "Edge";
        if (OPERA.matcher(userAgent).find()) return "Opera";
        if (CHROME.matcher(userAgent).find()) return "Chrome";
        if (FIREFOX.matcher(userAgent).find()) return "Firefox";
        if (SAFARI.matcher(userAgent).find()) return "Safari";
        return null;
    }

    private static String platform(String userAgent) {
        if (WINDOWS.matcher(userAgent).find()) return "Windows";
        if (ANDROID.matcher(userAgent).find()) return "Android";
        if (IOS.matcher(userAgent).find()) return "iOS";
        if (MAC.matcher(userAgent).find()) return "macOS";
        if (LINUX.matcher(userAgent).find()) return "Linux";
        return null;
    }
}
