package com.coc.docs.testsupport;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.text.ParseException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * Signs and verifies test-only Google-ID-token-shaped JWTs with a locally
 * generated RSA key — no network call to Google's real JWKS endpoint.
 *
 * <p>{@link #decoder()} mirrors what {@code spring-boot-starter-oauth2-resource-server}'s
 * autoconfiguration builds in production (timestamp + audience validation on
 * top of signature/issuer checking) closely enough to exercise
 * {@code GoogleIdTokenService}'s own logic realistically, without this test
 * suite depending on Spring Boot's autoconfiguration machinery itself or on
 * the network — that autoconfigured wiring is Spring's own well-tested code.
 */
public final class GoogleTestTokens {

    public static final String ISSUER = "https://accounts.google.com";
    public static final String AUDIENCE = "test-client-id.apps.googleusercontent.com";
    private static final String KEY_ID = "test-key";

    private final RSAPublicKey publicKey;
    private final RSAPrivateKey privateKey;

    public GoogleTestTokens() {
        KeyPair pair = generateKeyPair();
        this.publicKey = (RSAPublicKey) pair.getPublic();
        this.privateKey = (RSAPrivateKey) pair.getPrivate();
    }

    /** A well-formed set of Google-ID-token claims. Mutate the returned map before {@link #sign} to test a variation. */
    public Map<String, Object> defaultClaims() {
        Instant now = Instant.now();
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", ISSUER);
        claims.put("aud", AUDIENCE);
        claims.put("sub", "117000000000000000000");
        claims.put("iat", now.getEpochSecond());
        claims.put("exp", now.plusSeconds(300).getEpochSecond());
        claims.put("email", "person@example.com");
        claims.put("email_verified", true);
        claims.put("name", "Person Example");
        claims.put("nonce", "expected-nonce");
        return claims;
    }

    public String sign(Map<String, Object> claims) {
        try {
            JWTClaimsSet claimsSet = JWTClaimsSet.parse(claims);
            SignedJWT jwt =
                    new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY_ID).build(), claimsSet);
            jwt.sign(new RSASSASigner(privateKey));
            return jwt.serialize();
        } catch (JOSEException | ParseException e) {
            throw new IllegalStateException("Failed to sign test token", e);
        }
    }

    /** Trusts only this instance's own key, so a token signed by a *different* {@code GoogleTestTokens} fails signature validation. */
    public JwtDecoder decoder() {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(publicKey).build();
        OAuth2TokenValidator<Jwt> audienceValidator = jwt -> jwt.getAudience() != null
                && jwt.getAudience().contains(AUDIENCE)
                        ? OAuth2TokenValidatorResult.success()
                        : OAuth2TokenValidatorResult.failure(
                                new OAuth2Error("invalid_token", "wrong audience", null));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefault(), audienceValidator));
        return decoder;
    }

    private static KeyPair generateKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            // RSA is a mandatory JDK algorithm; this cannot happen.
            throw new IllegalStateException(e);
        }
    }
}
