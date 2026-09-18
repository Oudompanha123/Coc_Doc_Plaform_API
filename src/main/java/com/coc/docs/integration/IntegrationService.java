package com.coc.docs.integration;

import com.coc.docs.audit.AuditAction;
import com.coc.docs.audit.AuditRepository;
import com.coc.docs.auth.CurrentUser;
import com.coc.docs.web.ApiException;
import com.coc.docs.web.Jsonb;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Connect / rename / test / rotate / disconnect for outgoing webhook
 * integrations, and the dispatch fan-out real events trigger (ADR-037).
 *
 * <p>// req: FR-INTEG-001, FR-INTEG-002, FR-INTEG-003, FR-INTEG-004, FR-INTEG-005, ADR-037
 */
@Service
public class IntegrationService {

    private static final int SECRET_BYTES = 32;
    private static final short CURRENT_KEY_VERSION = 1;
    private static final int RECENT_DELIVERIES_LIMIT = 20;

    private final IntegrationRepository integrations;
    private final IntegrationDeliveryRepository deliveries;
    private final IntegrationDispatcher dispatcher;
    private final SecretBox secretBox;
    private final IntegrationProperties properties;
    private final AuditRepository audit;
    private final Jsonb jsonb;
    private final SecureRandom random = new SecureRandom();

    public IntegrationService(
            IntegrationRepository integrations,
            IntegrationDeliveryRepository deliveries,
            IntegrationDispatcher dispatcher,
            SecretBox secretBox,
            IntegrationProperties properties,
            AuditRepository audit,
            Jsonb jsonb) {
        this.integrations = integrations;
        this.deliveries = deliveries;
        this.dispatcher = dispatcher;
        this.secretBox = secretBox;
        this.properties = properties;
        this.audit = audit;
        this.jsonb = jsonb;
    }

    public List<Integration> list() {
        return integrations.list();
    }

    public List<IntegrationDelivery> recentDeliveries(UUID id) {
        return deliveries.recentFor(require(id).id(), RECENT_DELIVERIES_LIMIT);
    }

    public record Connected(Integration integration, String plaintextSecret) {}

    /** @throws ApiException 503 when {@code app.integrations.encryption-key} is unset on this deployment */
    @Transactional
    public Connected connect(String rawProvider, String rawName, String rawTargetUrl, List<String> rawEvents, CurrentUser actor) {
        requireEncryptionConfigured();
        String provider = normalizeProvider(rawProvider);
        String name = normalizeName(rawName);
        String targetUrl = normalizeAndValidateTargetUrl(rawTargetUrl);
        List<String> events = normalizeEvents(rawEvents);

        String plaintextSecret = generateSecret();
        SecretBox.Encrypted encrypted = secretBox.encrypt(plaintextSecret);

        Integration created = integrations.insert(
                provider, name, targetUrl, encrypted.iv(), encrypted.ciphertext(), CURRENT_KEY_VERSION, events, actor.id());

        audit.append(
                actor.id(),
                actor.email(),
                AuditAction.INTEGRATION_CONNECT,
                AuditAction.TARGET_INTEGRATION,
                created.id(),
                jsonb.mapper().createObjectNode().put("provider", provider).put("name", name));

        return new Connected(created, plaintextSecret);
    }

    @Transactional
    public Integration rename(UUID id, String rawName, List<String> rawEvents, CurrentUser actor) {
        Integration existing = require(id);
        String name = normalizeName(rawName);
        List<String> events = normalizeEvents(rawEvents);

        integrations.updateNameAndEvents(id, name, events);

        audit.append(
                actor.id(),
                actor.email(),
                AuditAction.INTEGRATION_UPDATE,
                AuditAction.TARGET_INTEGRATION,
                id,
                jsonb.mapper().createObjectNode().put("subject", existing.name()).put("name", name));

        return require(id);
    }

    public record TestResult(IntegrationDelivery delivery) {}

    /**
     * Fires a synchronous, HMAC-signed synthetic ping and records the
     * outcome — never blocks past {@link IntegrationDispatcher}'s own
     * connect/read timeouts.
     *
     * @throws ApiException 409 when the integration is disconnected — there
     *     is no secret left to sign with
     */
    public TestResult test(UUID id, CurrentUser actor) {
        Integration integration = require(id);
        if (!integration.isConnected()) {
            throw ApiException.conflict("this integration is disconnected — reconnect it before testing");
        }

        String body = buildPayload(integration.provider(), IntegrationDelivery.EVENT_TEST, jsonb.mapper().createObjectNode());
        IntegrationDispatcher.Outcome outcome = dispatcher.send(integration, IntegrationDelivery.EVENT_TEST, body);

        IntegrationDelivery recorded = deliveries.append(
                id, IntegrationDelivery.EVENT_TEST, outcome.httpStatus(), outcome.success(), outcome.error(), outcome.latencyMs());
        integrations.recordTestResult(id, outcome.success() ? "success" : "failed");

        // Not audited: a super_admin clicking "Test" is a read of this
        // integration's health, not a change to it — same reasoning
        // ClaudeUsageService.dashboard (a read) carries no audit.append,
        // while every roster mutation in that same class does.
        return new TestResult(recorded);
    }

    public record Rotated(Integration integration, String plaintextSecret) {}

    /** @throws ApiException 409 when the integration is disconnected; 503 when encryption is unconfigured */
    @Transactional
    public Rotated rotateSecret(UUID id, CurrentUser actor) {
        requireEncryptionConfigured();
        Integration existing = require(id);
        if (!existing.isConnected()) {
            throw ApiException.conflict("this integration is disconnected — reconnect it instead of rotating");
        }

        String plaintextSecret = generateSecret();
        SecretBox.Encrypted encrypted = secretBox.encrypt(plaintextSecret);
        integrations.updateSigningSecret(id, encrypted.iv(), encrypted.ciphertext(), CURRENT_KEY_VERSION);

        audit.append(
                actor.id(),
                actor.email(),
                AuditAction.INTEGRATION_ROTATE_SECRET,
                AuditAction.TARGET_INTEGRATION,
                id,
                jsonb.mapper().createObjectNode().put("subject", existing.name()));

        return new Rotated(require(id), plaintextSecret);
    }

    /**
     * Disconnects an integration: the secret columns are nulled in the same
     * statement that flips the status, so the credential is cryptographically
     * gone rather than merely flagged, while its delivery history survives.
     */
    @Transactional
    public Integration disconnect(UUID id, CurrentUser actor) {
        Integration existing = require(id);
        if (Integration.STATUS_DISABLED.equals(existing.status())) {
            return existing;
        }

        integrations.disconnect(id);

        audit.append(
                actor.id(),
                actor.email(),
                AuditAction.INTEGRATION_DISCONNECT,
                AuditAction.TARGET_INTEGRATION,
                id,
                jsonb.mapper().createObjectNode().put("subject", existing.name()));

        return require(id);
    }

    // -----------------------------------------------------------------------
    // Real-event fan-out — called from IntegrationRegisteredListener
    // -----------------------------------------------------------------------

    /**
     * Delivers {@code eventType} to every connected, subscribed integration.
     * Never rethrows — one dead endpoint must not stop delivery to the rest,
     * and there is no session/response left to report a failure to by the
     * time this runs ({@code @Async}, {@code AFTER_COMMIT}).
     */
    @Async
    public void dispatchEvent(String eventType) {
        for (Integration integration : integrations.findConnectedSubscribedTo(eventType)) {
            try {
                String body = buildPayload(integration.provider(), eventType, jsonb.mapper().createObjectNode());
                IntegrationDispatcher.Outcome outcome = dispatcher.send(integration, eventType, body);
                deliveries.append(
                        integration.id(), eventType, outcome.httpStatus(), outcome.success(), outcome.error(), outcome.latencyMs());
            } catch (RuntimeException e) {
                // Logged by the caller's own thread's uncaught-exception
                // handling is not enough here -- this really must not
                // propagate and abort delivery to every integration after it.
                deliveries.append(integration.id(), eventType, null, false, "dispatch failed: " + e.getMessage(), null);
            }
        }
    }

    // -----------------------------------------------------------------------
    // Payload shapes
    // -----------------------------------------------------------------------

    /**
     * Deliberately carries no account PII (email, display name, ids) —
     * {@code TOOL-002} (global rule): never send internal data to an
     * external SaaS/webhook target without authorization, and nothing in
     * {@code FR-INTEG-*} asks for the payload to carry any. An event name and
     * a timestamp are the only content, for every provider and every event.
     */
    private String buildPayload(String provider, String eventType, ObjectNode data) {
        ObjectNode envelope = jsonb.mapper().createObjectNode();
        envelope.put("event", eventType);
        envelope.put("occurredAt", Instant.now().toString());
        envelope.set("data", data);

        if (Integration.PROVIDER_SLACK.equals(provider)) {
            String text = eventType.equals(IntegrationDelivery.EVENT_TEST)
                    ? "Coc Docs Platform: this integration is connected and working."
                    : "Coc Docs Platform: " + eventType + " fired.";
            return jsonb.write(jsonb.mapper().createObjectNode().put("text", text));
        }
        return jsonb.write(envelope);
    }

    // -----------------------------------------------------------------------
    // Validation
    // -----------------------------------------------------------------------

    private void requireEncryptionConfigured() {
        if (!properties.configured()) {
            throw new ApiException(
                    org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                    "integrations are not configured on this deployment");
        }
    }

    private static String normalizeProvider(String raw) {
        String provider = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (!Integration.PROVIDER_WEBHOOK.equals(provider) && !Integration.PROVIDER_SLACK.equals(provider)) {
            throw ApiException.badRequest("provider must be \"webhook\" or \"slack\"");
        }
        return provider;
    }

    private static String normalizeName(String raw) {
        if (raw == null || raw.isBlank()) {
            throw ApiException.badRequest("name is required");
        }
        String name = raw.trim();
        if (name.length() > 200) {
            throw ApiException.badRequest("name must be 200 characters or fewer");
        }
        return name;
    }

    private static List<String> normalizeEvents(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            throw ApiException.badRequest("at least one event is required");
        }
        List<String> events = raw.stream().filter(e -> e != null && !e.isBlank()).map(String::trim).distinct().toList();
        for (String event : events) {
            if (!IntegrationEvents.KNOWN.contains(event)) {
                throw ApiException.badRequest("unknown event type: " + event);
            }
        }
        if (events.isEmpty()) {
            throw ApiException.badRequest("at least one event is required");
        }
        return events;
    }

    /**
     * Rejects anything that is not a plain {@code http}/{@code https} URL
     * whose host resolves to a public address — ADR-037 §6's SSRF posture,
     * decided (not merely recommended) at implementation time: a
     * {@code super_admin} session is this system's highest trust level, but
     * it should not be a free pivot into whatever network this backend runs
     * on. Resolves the host once, at connect/rotate/test time, not on every
     * delivery — DNS can change, but re-litigating this on every dispatch
     * would need its own decision about what to do with a rebinding attack,
     * which is out of scope for a first version.
     */
    private static String normalizeAndValidateTargetUrl(String raw) {
        if (raw == null || raw.isBlank()) {
            throw ApiException.badRequest("targetUrl is required");
        }
        String targetUrl = raw.trim();
        URI uri;
        try {
            uri = URI.create(targetUrl);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("targetUrl is not a valid URL");
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw ApiException.badRequest("targetUrl must start with http:// or https://");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw ApiException.badRequest("targetUrl must include a host");
        }
        try {
            InetAddress address = InetAddress.getByName(host);
            if (address.isLoopbackAddress()
                    || address.isLinkLocalAddress()
                    || address.isSiteLocalAddress()
                    || address.isAnyLocalAddress()) {
                throw ApiException.badRequest("targetUrl must not point at a private, loopback or link-local address");
            }
        } catch (UnknownHostException e) {
            throw ApiException.badRequest("targetUrl's host could not be resolved");
        }
        return targetUrl;
    }

    private String generateSecret() {
        byte[] bytes = new byte[SECRET_BYTES];
        random.nextBytes(bytes);
        return "whsec_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private Integration require(UUID id) {
        return integrations.findById(id).orElseThrow(() -> ApiException.notFound("integration " + id));
    }
}
