package com.coc.docs.notify;

import com.coc.docs.config.AppProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

/**
 * Turns a Google authorization code into a refresh token, and a refresh token
 * into the short-lived access token that SMTP and IMAP actually authenticate
 * with (ADR-085).
 *
 * <p><b>Why a second exchange at all.</b> Signing in with Google carries an ID
 * token, which asserts who somebody is and grants nothing. A refresh token
 * comes only from the authorization-code flow, with {@code access_type=offline}
 * and the client secret presented server-side. The two are different
 * conversations with the same provider, and no amount of reading the ID token
 * produces the other — ADR-085 §1 records that, because it is the question
 * everyone asks first.
 *
 * <p><b>Access tokens are never persisted.</b> They live an hour; this holds
 * them in a map keyed by account and re-mints on expiry. A process restart
 * loses the cache and costs one HTTPS round trip, which is the right price for
 * not having another secret at rest.
 *
 * <p>// req: FR-OPS-001, NFR-SEC-PII, NFR-SEC-CHANNEL, ADR-085
 */
@Service
public class GoogleMailOAuthService {

    private static final Logger log = LoggerFactory.getLogger(GoogleMailOAuthService.class);

    /** Google's token endpoint. One constant, so a test can point elsewhere. */
    static final String TOKEN_URI = "https://oauth2.googleapis.com/token";

    /** Send as the user, and read their mail. Nothing wider — ADR-085 §2. */
    public static final String SCOPES =
            "https://www.googleapis.com/auth/gmail.send https://www.googleapis.com/auth/gmail.readonly";

    /**
     * What a popup-based code client uses in place of a redirect URI.
     *
     * <p>Google's own magic value for {@code ux_mode: "popup"}. A real URI here
     * makes the exchange fail with {@code redirect_uri_mismatch}, which is the
     * single most common way this flow is got wrong.
     */
    private static final String POPUP_REDIRECT = "postmessage";

    /**
     * Re-mint this long before the token actually expires.
     *
     * <p>A token that is valid when checked and expired when the relay reads it
     * fails a send for no reason. Sixty seconds covers clock skew between this
     * host and Google and the time an IMAP handshake takes.
     */
    private static final Duration EARLY = Duration.ofSeconds(60);

    private record CachedToken(String value, Instant expiresAt) {
        boolean usable() {
            return Instant.now().isBefore(expiresAt.minus(EARLY));
        }
    }

    private final Map<java.util.UUID, CachedToken> cache = new ConcurrentHashMap<>();
    private final RestClient http;
    private final AppProperties properties;

    public GoogleMailOAuthService(AppProperties properties, RestClient.Builder builder) {
        this.properties = properties;
        this.http = builder.build();
    }

    /** Whether the code exchange can be attempted — both halves of the client are needed. */
    public boolean isConfigured() {
        return properties.googleClientId() != null
                && !properties.googleClientId().isBlank()
                && properties.googleClientSecret() != null
                && !properties.googleClientSecret().isBlank();
    }

    /** What Google returned for an authorization code. */
    public record Grant(String refreshToken, String scope) {}

    /**
     * Exchanges an authorization code for a refresh token.
     *
     * <p>Empty rather than an exception for a refused code: a person closing
     * the consent popup, or taking long enough for the code to expire, is
     * ordinary and the caller answers it with "not connected", not a 500.
     *
     * <p><b>Google omits {@code refresh_token} when it has already issued one
     * to this client for this user.</b> The exchange then "succeeds" and
     * stores nothing, which looks exactly like a bug and is not one. The fix
     * is on the person's side — remove this app under their Google account's
     * third-party access and grant again — so the warning below says what
     * happened rather than failing silently.
     */
    public Optional<Grant> exchangeCode(String authorizationCode) {
        if (!isConfigured() || authorizationCode == null || authorizationCode.isBlank()) {
            return Optional.empty();
        }

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("code", authorizationCode);
        form.add("client_id", properties.googleClientId());
        form.add("client_secret", properties.googleClientSecret());
        form.add("redirect_uri", POPUP_REDIRECT);
        form.add("grant_type", "authorization_code");

        try {
            Map<?, ?> body = http.post()
                    .uri(TOKEN_URI)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(Map.class);

            String refresh = body == null ? null : asString(body.get("refresh_token"));
            if (refresh == null || refresh.isBlank()) {
                log.warn("gmail oauth: Google returned no refresh token — this app is probably already "
                        + "authorised for that account; remove it under Google account > Data & privacy > "
                        + "Third-party access, then grant again");
                return Optional.empty();
            }
            return Optional.of(new Grant(refresh, asString(body.get("scope"))));
        } catch (RuntimeException e) {
            // Never the code, never the secret, never the token.
            log.warn("gmail oauth: could not exchange an authorization code ({})", e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    /**
     * A usable access token for this account, minted from its refresh token.
     *
     * <p>Cached per account until shortly before it expires. Empty when Google
     * refuses the refresh token — revoked, expired after six months unused, or
     * invalidated by a password change. The caller falls back rather than
     * failing somebody's attendance mail, which is the same contract the App
     * Password path has.
     */
    public Optional<String> accessTokenFor(java.util.UUID accountId, String refreshToken) {
        CachedToken cached = cache.get(accountId);
        if (cached != null && cached.usable()) {
            return Optional.of(cached.value());
        }
        if (!isConfigured() || refreshToken == null || refreshToken.isBlank()) {
            return Optional.empty();
        }

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("client_id", properties.googleClientId());
        form.add("client_secret", properties.googleClientSecret());
        form.add("refresh_token", refreshToken);
        form.add("grant_type", "refresh_token");

        try {
            Map<?, ?> body = http.post()
                    .uri(TOKEN_URI)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(Map.class);

            String token = body == null ? null : asString(body.get("access_token"));
            if (token == null || token.isBlank()) {
                return Optional.empty();
            }
            long seconds = body.get("expires_in") instanceof Number n ? n.longValue() : 3600L;
            cache.put(accountId, new CachedToken(token, Instant.now().plusSeconds(seconds)));
            return Optional.of(token);
        } catch (RuntimeException e) {
            // A revoked grant lands here on every poll until somebody
            // re-grants it, so this must stay cheap and quiet: the class name,
            // no token, no stack.
            log.warn("gmail oauth: could not refresh an access token ({})", e.getClass().getSimpleName());
            cache.remove(accountId);
            return Optional.empty();
        }
    }

    /** Drops any cached access token for this account — on revoke, or on a credential change. */
    public void forget(java.util.UUID accountId) {
        cache.remove(accountId);
    }

    private static String asString(Object value) {
        return value instanceof String s ? s : null;
    }
}
