package com.coc.docs.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.coc.docs.audit.AuditEntry;
import com.coc.docs.notify.NotificationFeedService;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Rewriting a stored photo URL into this API's own avatar route (ADR-101).
 *
 * <p>The rule is narrow on purpose: only an address that looks like one this
 * system wrote is touched. Rewriting anything else would break the identity
 * provider's picture, which is a working URL this system does not own.
 *
 * <p>// req: FR-AUTH-005, NFR-SEC-PII, ADR-101
 */
class AvatarHrefTest {

    private static final String ACCOUNT = "11111111-2222-3333-4444-555555555555";
    private static final String FILE = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee.png";

    @Test
    @DisplayName("rewrites a stored S3 object URL to the avatar route")
    void rewritesStoredUrl() {
        String stored = "https://s3.ap-southeast-1.amazonaws.com/my-bucket/avatars/" + ACCOUNT + "/" + FILE;

        assertThat(ApiDtos.avatarHref(stored)).isEqualTo("/api/v1/avatars/" + ACCOUNT + "/" + FILE);
    }

    /** MinIO, a different region, a path-style host — all the same shape. */
    @Test
    @DisplayName("does not depend on the endpoint or bucket name")
    void endpointAgnostic() {
        assertThat(ApiDtos.avatarHref("http://minio:9000/anything/avatars/" + ACCOUNT + "/" + FILE))
                .isEqualTo("/api/v1/avatars/" + ACCOUNT + "/" + FILE);
    }

    /**
     * Google's picture URL is not ours and already works. Rewriting it would
     * point the browser at a route that has no object to sign.
     */
    @Test
    @DisplayName("leaves an identity provider picture untouched")
    void leavesProviderPicture() {
        String google = "https://lh3.googleusercontent.com/a/ACg8ocK=s96-c";

        assertThat(ApiDtos.avatarHref(google)).isEqualTo(google);
    }

    @Test
    @DisplayName("null stays null, so the client falls back to initials")
    void nullStaysNull() {
        assertThat(ApiDtos.avatarHref(null)).isNull();
    }

    /**
     * Already-rewritten input must not be rewritten twice. It reaches here if
     * a value is ever round-tripped through the API, and doubling the prefix
     * would produce a 404 that is hard to trace back to this method.
     */
    @Test
    @DisplayName("is idempotent")
    void idempotent() {
        String once = ApiDtos.avatarHref(
                "https://s3.test/bucket/avatars/" + ACCOUNT + "/" + FILE);

        assertThat(ApiDtos.avatarHref(once)).isEqualTo(once);
    }

    /**
     * Overview's Recent Activity and the bell's notifications showed an
     * uploaded photo locally but initials when deployed: they sent the direct
     * bucket URL, which only loads while the bucket is public. They must go
     * through the same route as every other picture.
     */
    @Test
    @DisplayName("Recent Activity rows send the avatar route, not the bucket URL")
    void recentActivityUsesTheRoute() {
        String stored = "https://s3.ap-southeast-1.amazonaws.com/my-bucket/avatars/" + ACCOUNT + "/" + FILE;
        AuditEntry entry = new AuditEntry(
                1L, UUID.randomUUID(), "dana@example.com", stored, "auth.sign_in", "account", null, null, Instant.now());

        assertThat(ApiDtos.ActivityEntryResponse.of(entry).actorAvatarUrl())
                .isEqualTo("/api/v1/avatars/" + ACCOUNT + "/" + FILE);
    }

    @Test
    @DisplayName("notification items send the avatar route, not the bucket URL")
    void notificationsUseTheRoute() {
        String stored = "http://minio:9000/docs/avatars/" + ACCOUNT + "/" + FILE;
        NotificationFeedService.FeedItem item = new NotificationFeedService.FeedItem(
                "n-1", "dana@example.com", stored, "Dana published a page", Instant.now(), "publish", "page", null, "/x");

        assertThat(ApiDtos.NotificationItem.of(item).actorAvatarUrl())
                .isEqualTo("/api/v1/avatars/" + ACCOUNT + "/" + FILE);
    }

    @Test
    @DisplayName("a Google picture and a missing picture pass through both unchanged")
    void providerAndMissingPassThrough() {
        String google = "https://lh3.googleusercontent.com/a/ACg8ocK=s96-c";
        AuditEntry withGoogle = new AuditEntry(
                1L, UUID.randomUUID(), "dana@example.com", google, "auth.sign_in", "account", null, null, Instant.now());
        NotificationFeedService.FeedItem withNone = new NotificationFeedService.FeedItem(
                "n-2", "dana@example.com", null, "s", Instant.now(), "publish", "page", null, "/x");

        assertThat(ApiDtos.ActivityEntryResponse.of(withGoogle).actorAvatarUrl()).isEqualTo(google);
        assertThat(ApiDtos.NotificationItem.of(withNone).actorAvatarUrl()).isNull();
    }
}
