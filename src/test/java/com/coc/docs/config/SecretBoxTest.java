package com.coc.docs.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** // req: NFR-SEC-PII, ADR-063 */
class SecretBoxTest {

    private static String key(byte fill) {
        byte[] raw = new byte[32];
        java.util.Arrays.fill(raw, fill);
        return Base64.getEncoder().encodeToString(raw);
    }

    private final SecretBox box = new SecretBox(key((byte) 7));

    @Test
    @DisplayName("round-trips")
    void roundTrips() {
        assertThat(box.decrypt(box.encrypt("xoqrdatgsiysekwv"))).isEqualTo("xoqrdatgsiysekwv");
    }

    @Test
    @DisplayName("the stored form is not the plaintext, and differs every time -- a fresh nonce per encryption")
    void storedFormIsOpaqueAndNonRepeating() {
        String a = box.encrypt("same-secret");
        String b = box.encrypt("same-secret");

        assertThat(a).doesNotContain("same-secret");
        assertThat(a).isNotEqualTo(b);
        assertThat(box.decrypt(b)).isEqualTo("same-secret");
    }

    @Test
    @DisplayName("another key cannot read it -- the property the whole table rests on")
    void anotherKeyCannotRead() {
        String sealed = box.encrypt("xoqrdatgsiysekwv");

        assertThatThrownBy(() -> new SecretBox(key((byte) 9)).decrypt(sealed))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("a tampered value fails rather than decrypting to rubbish -- GCM's tag, not a guess")
    void tamperingFails() {
        char[] c = box.encrypt("xoqrdatgsiysekwv").toCharArray();
        c[c.length - 2] = c[c.length - 2] == 'A' ? 'B' : 'A';

        assertThatThrownBy(() -> box.decrypt(new String(c))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("no key means refusal, never silent plaintext")
    void noKeyRefuses() {
        SecretBox unconfigured = new SecretBox("");

        assertThat(unconfigured.isConfigured()).isFalse();
        assertThatThrownBy(() -> unconfigured.encrypt("x")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("a key of the wrong length is refused at startup, not at first use")
    void wrongLengthKeyRefusedAtStartup() {
        String short16 = Base64.getEncoder().encodeToString(new byte[16]);

        assertThatThrownBy(() -> new SecretBox(short16))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 bytes");
    }
}
