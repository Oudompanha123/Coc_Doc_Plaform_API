package com.coc.docs.config;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Reversible encryption for the few values this application must be able to
 * read back — today, an account's own Gmail App Password (ADR-063).
 *
 * <p><b>Reversible is the whole point, and the whole cost.</b> Everything else
 * secret here is hashed: {@code password_hash}, session tokens, reset tokens,
 * registration codes. A hash cannot authenticate to Gmail, so this value
 * cannot be one — which means a key exists, and {@code ADR-007}'s "no
 * application-layer column encryption to key" stops being true. That is stated
 * in ADR-063 §4.2 rather than discovered later.
 *
 * <p>AES-256-GCM: authenticated, so a tampered ciphertext fails to decrypt
 * rather than yielding plausible rubbish. A fresh 12-byte nonce per encryption,
 * prepended to the ciphertext, because reusing a nonce under one key is the
 * mistake that breaks GCM outright.
 *
 * <p><b>What this does not defend against.</b> The key is an environment
 * variable on the same host as the database credentials, so it protects a
 * stolen dump and not a compromised host. ADR-063 §4.2 says so plainly; this
 * comment exists so nobody reads "encrypted at rest" here and concludes more
 * than that.
 *
 * <p>// req: NFR-SEC-PII, NFR-SEC-CHANNEL, ADR-063
 */
@Component
public class SecretBox {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecureRandom random = new SecureRandom();
    private final SecretKeySpec key;

    /**
     * @param base64Key a base64-encoded 32-byte key. Blank disables the box:
     *     it then refuses to encrypt rather than silently storing plaintext,
     *     which is the failure mode worth having.
     */
    public SecretBox(@Value("${app.security.encryption-key:}") String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            this.key = null;
            return;
        }
        byte[] raw = Base64.getDecoder().decode(base64Key.trim());
        if (raw.length != 32) {
            throw new IllegalStateException(
                    "app.security.encryption-key must decode to 32 bytes (AES-256); got " + raw.length);
        }
        this.key = new SecretKeySpec(raw, "AES");
    }

    /** Whether a key is configured at all. Callers refuse the feature rather than store plaintext. */
    public boolean isConfigured() {
        return key != null;
    }

    /** Nonce ‖ ciphertext ‖ tag, base64. */
    public String encrypt(String plaintext) {
        requireKey();
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            random.nextBytes(nonce);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            byte[] sealed = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            byte[] out = new byte[nonce.length + sealed.length];
            System.arraycopy(nonce, 0, out, 0, nonce.length);
            System.arraycopy(sealed, 0, out, nonce.length, sealed.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            // Deliberately not carrying the plaintext into the message.
            throw new IllegalStateException("could not encrypt a stored secret", e);
        }
    }

    /**
     * @throws IllegalStateException if the value was not produced by this key —
     *     GCM's tag check makes that a failure rather than garbage
     */
    public String decrypt(String stored) {
        requireKey();
        try {
            byte[] all = Base64.getDecoder().decode(stored);
            byte[] nonce = new byte[NONCE_BYTES];
            System.arraycopy(all, 0, nonce, 0, NONCE_BYTES);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            byte[] plain = cipher.doFinal(all, NONCE_BYTES, all.length - NONCE_BYTES);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("could not decrypt a stored secret", e);
        }
    }

    private void requireKey() {
        if (key == null) {
            throw new IllegalStateException(
                    "app.security.encryption-key is not set, so secrets cannot be stored or read");
        }
    }
}
