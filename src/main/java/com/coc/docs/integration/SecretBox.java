package com.coc.docs.integration;

import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * AES-256-GCM encryption for one column: an integration's signing secret.
 *
 * <p>This codebase's first application-layer encryption-at-rest primitive
 * (ADR-037, revising {@code ADR-INDEX.md}'s ADR-007 "no encryption keys of
 * its own to manage" record) — nothing before this needed one, because every
 * prior secret either lived outside the database entirely (env vars) or was
 * hashed one-way (bcrypt passwords, which is not decryptable and so is not a
 * model for a credential this system must later present to a third party).
 *
 * <p>GCM, not CBC: authenticated encryption means a tampered ciphertext fails
 * to decrypt rather than silently producing garbage plaintext. A fresh random
 * 96-bit IV per call — GCM's own requirement is that an (key, IV) pair is
 * never reused, and a random IV this size makes a collision negligible for
 * the volume of rows this table will ever hold.
 *
 * <p>// req: FR-INTEG-001, FR-INTEG-002, ADR-037
 */
@Component
public class SecretBox {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final IntegrationProperties properties;
    private final SecureRandom random = new SecureRandom();

    public SecretBox(IntegrationProperties properties) {
        this.properties = properties;
    }

    public record Encrypted(byte[] iv, byte[] ciphertext) {}

    /** @throws IllegalStateException when {@code app.integrations.encryption-key} is unset or malformed */
    public Encrypted encrypt(String plaintext) {
        byte[] key = requireKey();
        byte[] iv = new byte[IV_BYTES];
        random.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, iv));
            return new Encrypted(iv, cipher.doFinal(plaintext.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            // The key is well-formed (requireKey already checked) and the
            // transformation is a JDK-guaranteed one -- reaching here means
            // something is wrong with the JVM's crypto provider, not with the
            // caller's input.
            throw new IllegalStateException("could not encrypt integration secret", e);
        }
    }

    /** @throws IllegalStateException when the key is unset/malformed, or the ciphertext fails authentication */
    public String decrypt(byte[] iv, byte[] ciphertext) {
        byte[] key = requireKey();
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(ciphertext), java.nio.charset.StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            // Wrong key, corrupted row, or a tampered ciphertext -- GCM's
            // authentication tag failed to verify. Never distinguished from
            // "wrong key" in the message: either way there is nothing a
            // caller can act on except treating the secret as unusable.
            throw new IllegalStateException("could not decrypt integration secret", e);
        }
    }

    private byte[] requireKey() {
        byte[] key = properties.decodedKeyOrNull();
        if (key == null) {
            throw new IllegalStateException(
                    "app.integrations.encryption-key is not configured on this deployment");
        }
        return key;
    }
}
