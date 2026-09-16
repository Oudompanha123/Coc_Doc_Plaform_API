package com.coc.docs.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The rules that decide whether a sign-in is announced as unfamiliar.
 *
 * <p>Worth real tests despite being string matching, because both failure
 * directions are bad in ways nobody would notice: a description that changes
 * between browser versions cries wolf until people stop reading, and one that
 * collapses two browsers into the same name stays silent when it should not.
 *
 * <p>// req: FR-AUTH-007, ADR-027
 */
class DeviceDescriptionTest {

    private static final String CHROME_WINDOWS =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko)"
                    + " Chrome/140.0.0.0 Safari/537.36";
    private static final String EDGE_WINDOWS =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko)"
                    + " Chrome/140.0.0.0 Safari/537.36 Edg/140.0.0.0";
    private static final String FIREFOX_MAC =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10.15; rv:130.0) Gecko/20100101 Firefox/130.0";
    private static final String SAFARI_IPHONE =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15"
                    + " (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1";
    private static final String CHROME_ANDROID =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko)"
                    + " Chrome/140.0.0.0 Mobile Safari/537.36";

    @Test
    @DisplayName("names the browser and the platform")
    void describesBrowserAndPlatform() {
        assertThat(DeviceDescription.describe(CHROME_WINDOWS)).isEqualTo("Chrome on Windows");
        assertThat(DeviceDescription.describe(FIREFOX_MAC)).isEqualTo("Firefox on macOS");
        assertThat(DeviceDescription.describe(SAFARI_IPHONE)).isEqualTo("Safari on iOS");
    }

    @Test
    @DisplayName("Edge is not Chrome, though it says Chrome in its user agent")
    void mostSpecificBrowserWins() {
        // Edge's user agent contains both "Chrome/" and "Safari/". Test order
        // is the whole implementation here, so this is the test that fails if
        // somebody tidies the conditions into alphabetical order.
        assertThat(DeviceDescription.describe(EDGE_WINDOWS)).isEqualTo("Edge on Windows");
        assertThat(DeviceDescription.key(EDGE_WINDOWS))
                .isNotEqualTo(DeviceDescription.key(CHROME_WINDOWS));
    }

    @Test
    @DisplayName("Android is not Linux, though it says Linux in its user agent")
    void mostSpecificPlatformWins() {
        assertThat(DeviceDescription.describe(CHROME_ANDROID)).isEqualTo("Chrome on Android");
    }

    @Test
    @DisplayName("a browser update is not a new device")
    void versionIsNotPartOfTheKey() {
        // The reason there is no version in the description at all. If this
        // ever fails, every user gets a security alert on Patch Tuesday and
        // learns to dismiss them.
        String updated = CHROME_WINDOWS.replace("Chrome/140.0.0.0", "Chrome/141.0.0.0");

        assertThat(DeviceDescription.key(updated)).isEqualTo(DeviceDescription.key(CHROME_WINDOWS));
    }

    @Test
    @DisplayName("two sessions with no user agent recognise each other")
    void missingUserAgentIsItsOwnDevice() {
        // Null would make every such session unmatched, and an account signing
        // in from a client that sends no user agent would be warned about a new
        // device every single time.
        assertThat(DeviceDescription.key(null)).isEqualTo(DeviceDescription.key(""));
        assertThat(DeviceDescription.key(null)).isEqualTo(DeviceDescription.UNKNOWN_KEY);
        assertThat(DeviceDescription.describe(null)).isEqualTo("Unknown device");
    }

    @Test
    @DisplayName("an unrecognised user agent is shown, not hidden behind 'Unknown'")
    void unknownAgentFallsBackToItsOwnText() {
        // "Unknown device" helps nobody decide whether to end a session; a
        // string they can search for might.
        assertThat(DeviceDescription.describe("curl/8.4.0")).isEqualTo("curl/8.4.0");
    }

    @Test
    @DisplayName("the key is case-insensitive, the description is not")
    void keyIsFolded() {
        assertThat(DeviceDescription.key(CHROME_WINDOWS)).isEqualTo("chrome on windows");
        assertThat(DeviceDescription.describe(CHROME_WINDOWS)).isEqualTo("Chrome on Windows");
    }
}
