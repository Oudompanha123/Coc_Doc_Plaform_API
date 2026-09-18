package com.coc.docs.integration;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Sends one HMAC-signed HTTP POST to one integration's target URL, and
 * nothing else — no retry, no queueing, no knowledge of which event types
 * exist or which integrations are subscribed to them. {@link IntegrationService}
 * owns that; this class owns only "make the call, time it, never throw
 * past its own boundary."
 *
 * <p><b>Explicit timeouts, no retry loop</b> — {@code CLAUDE.md}'s CODE-002
 * ("no missing timeouts or infinite retry loops for external API calls"),
 * and this codebase's first hand-timed outbound HTTP client: every existing
 * external call (Google's JWKS fetch, S3, Keycloak) relies on its own SDK's
 * defaults rather than a value chosen here. 3s to connect, 5s to read a
 * response — generous enough for a slow but working endpoint, short enough
 * that a dead one cannot hold up the caller (the synchronous "Test" action)
 * or the async dispatcher's worker thread for long.
 *
 * <p>The signature lets a receiver that supports it verify the payload came
 * from this deployment and was not altered in transit (ADR-037 §7,
 * revising ADR-004's "no message-passing architecture" N/A record) — HMAC-
 * SHA256 over the exact JSON bytes sent, hex-encoded, in
 * {@code X-CocDocs-Signature}. A receiver that does not check it (a plain
 * Slack incoming webhook, for instance) simply ignores the header.
 *
 * <p>// req: FR-INTEG-003, FR-INTEG-005, ADR-037
 */
@Component
public class IntegrationDispatcher {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final SecretBox secretBox;
    private final RestClient restClient;

    public IntegrationDispatcher(SecretBox secretBox) {
        this.secretBox = secretBox;
        this.restClient = RestClient.builder().requestFactory(timeoutedRequestFactory()).build();
    }

    private static SimpleClientHttpRequestFactory timeoutedRequestFactory() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) CONNECT_TIMEOUT.toMillis());
        factory.setReadTimeout((int) READ_TIMEOUT.toMillis());
        return factory;
    }

    /** The outcome of one delivery attempt — everything {@code integration_delivery} records. */
    public record Outcome(Integer httpStatus, boolean success, String error, int latencyMs) {}

    /**
     * @param integration must carry a non-null signing secret (i.e. be
     *     {@code connected}) — the caller (IntegrationService) is responsible
     *     for that precondition; this method does not re-check {@code status}.
     */
    public Outcome send(Integration integration, String eventType, String bodyJson) {
        String signature = sign(integration, bodyJson);
        long start = System.nanoTime();
        try {
            restClient
                    .post()
                    .uri(integration.targetUrl())
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-CocDocs-Event", eventType)
                    .header("X-CocDocs-Signature", signature)
                    .body(bodyJson)
                    .retrieve()
                    .toBodilessEntity();
            return new Outcome(200, true, null, elapsedMs(start));
            // A 2xx that this client library treats as success. A non-2xx status
            // is thrown by RestClient as an exception, caught below with the
            // real status code preserved.
        } catch (org.springframework.web.client.HttpStatusCodeException e) {
            HttpStatusCode status = e.getStatusCode();
            return new Outcome(status.value(), false, "HTTP " + status.value(), elapsedMs(start));
        } catch (RestClientException e) {
            // Connect/read timeout, DNS failure, connection refused, TLS
            // failure, and so on — no HTTP status was ever received.
            return new Outcome(null, false, describe(e), elapsedMs(start));
        }
    }

    private String sign(Integration integration, String bodyJson) {
        String secret = secretBox.decrypt(integration.signingSecretIv(), integration.signingSecretCiphertext());
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            byte[] digest = mac.doFinal(bodyJson.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (java.security.GeneralSecurityException e) {
            // HmacSHA256 is a JDK-guaranteed algorithm; reaching here means the
            // JVM's crypto provider is broken, not that the input was bad.
            throw new IllegalStateException("could not sign integration payload", e);
        }
    }

    private static int elapsedMs(long startNanos) {
        return (int) Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
    }

    private static String describe(RestClientException e) {
        String message = e.getMostSpecificCause().getMessage();
        return message != null && !message.isBlank() ? message : e.getClass().getSimpleName();
    }
}
