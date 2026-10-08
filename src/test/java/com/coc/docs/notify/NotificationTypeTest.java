package com.coc.docs.notify;

import static org.assertj.core.api.Assertions.assertThat;

import com.coc.docs.account.AppRole;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Where a tapped notification lands.
 *
 * <p>// req: ADR-025, ADR-027
 */
class NotificationTypeTest {

    private static final String ORIGIN = "https://docs.example.com";

    @Test
    @DisplayName("a path that already has a query gets the subject appended with &")
    void appendsToAnExistingQuery() {
        assertThat(NotificationType.NEW_REGISTRATION.deepLink(ORIGIN, "abc"))
                .isEqualTo("https://docs.example.com/users-roles?tab=pending&approve=abc");
    }

    @Test
    @DisplayName("a path with no query starts one with ?")
    void startsAQueryWhenThereIsNone() {
        // The bug this replaces: the separator was a hardcoded "&", which
        // happened to be right for the only type that existed. The second type
        // would have produced /settings&session=abc — a path, not a query, so
        // the client would have found no session id and opened a page with
        // nothing on it.
        assertThat(NotificationType.NEW_DEVICE_SIGN_IN.deepLink(ORIGIN, "abc"))
                .isEqualTo("https://docs.example.com/settings?session=abc");
    }

    @Test
    @DisplayName("no subject means no query string at all")
    void omitsTheParameterWhenThereIsNoSubject() {
        assertThat(NotificationType.NEW_DEVICE_SIGN_IN.deepLink(ORIGIN, null))
                .isEqualTo("https://docs.example.com/settings");
    }

    @Test
    @DisplayName("every type names the parameter its own page reads")
    void eachTypeCarriesItsOwnParameterName() {
        assertThat(NotificationType.NEW_REGISTRATION.deepLink(ORIGIN, "x")).contains("approve=x");
        assertThat(NotificationType.NEW_DEVICE_SIGN_IN.deepLink(ORIGIN, "x")).contains("session=x");
        assertThat(NotificationType.AX_RENEWAL_MEMBER_ADDED.deepLink(ORIGIN, "x")).contains("project=x");
        assertThat(NotificationType.AX_RENEWAL_RUN_READY.deepLink(ORIGIN, "x")).contains("project=x");
        assertThat(NotificationType.AX_RENEWAL_COMMENT_ADDED.deepLink(ORIGIN, "x")).contains("project=x");
        assertThat(NotificationType.AX_RENEWAL_RUN_REVIEWED.deepLink(ORIGIN, "x")).contains("project=x");
    }

    @Test
    @DisplayName("a member-added notification opens that project in the AX Renewal workspace (ADR-075)")
    void memberAddedOpensTheProject() {
        // `project=` is the query parameter useAxRenewalProject reads to pick
        // the open project.
        assertThat(NotificationType.AX_RENEWAL_MEMBER_ADDED.deepLink(ORIGIN, "p1"))
                .isEqualTo("https://docs.example.com/ax-renewal?project=p1");
    }

    @Test
    @DisplayName("a member-added notification's lock-screen sentence names no project")
    void memberAddedSaysNothingIdentifying() {
        // Fixed text, so the one thing it can never carry is which legacy
        // system a team is renewing (ADR-025 decision 6).
        assertThat(NotificationType.AX_RENEWAL_MEMBER_ADDED.body()).isEqualTo("You were added to an AX Renewal project");
        assertThat(NotificationType.AX_RENEWAL_MEMBER_ADDED.key()).isEqualTo("ax-renewal.member-added");
    }

    @Test
    @DisplayName("a run-ready notification opens that project, and its lock-screen sentence names none (ADR-079)")
    void runReadyOpensTheProjectAndSaysNothingIdentifying() {
        assertThat(NotificationType.AX_RENEWAL_RUN_READY.deepLink(ORIGIN, "p1"))
                .isEqualTo("https://docs.example.com/ax-renewal?project=p1");
        assertThat(NotificationType.AX_RENEWAL_RUN_READY.body())
                .isEqualTo("An AX Renewal implementation run is ready for review");
        assertThat(NotificationType.AX_RENEWAL_RUN_READY.key()).isEqualTo("ax-renewal.run-ready");
    }

    @Test
    @DisplayName("a comment notification opens that project, and its lock-screen sentence carries no text (ADR-080)")
    void commentAddedOpensTheProjectAndSaysNothingIdentifying() {
        assertThat(NotificationType.AX_RENEWAL_COMMENT_ADDED.deepLink(ORIGIN, "p1"))
                .isEqualTo("https://docs.example.com/ax-renewal?project=p1");
        assertThat(NotificationType.AX_RENEWAL_COMMENT_ADDED.body())
                .isEqualTo("Someone commented on an AX Renewal project");
        assertThat(NotificationType.AX_RENEWAL_COMMENT_ADDED.key()).isEqualTo("ax-renewal.comment-added");
    }

    @Test
    @DisplayName("a run-reviewed notification opens that project; its sentences name neither it nor the reviewer (ADR-081)")
    void runReviewedOpensTheProjectAndSaysNothingIdentifying() {
        assertThat(NotificationType.AX_RENEWAL_RUN_REVIEWED.deepLink(ORIGIN, "p1"))
                .isEqualTo("https://docs.example.com/ax-renewal?project=p1");
        assertThat(NotificationType.AX_RENEWAL_RUN_REVIEWED.key()).isEqualTo("ax-renewal.run-reviewed");
        assertThat(NotificationType.AX_RENEWAL_RUN_REVIEWED.body())
                .isEqualTo("Your AX Renewal implementation run was reviewed");
        assertThat(NotificationType.RUN_APPROVED_BODY)
                .isEqualTo("Your AX Renewal implementation run was approved and applied");
        assertThat(NotificationType.RUN_REJECTED_BODY).isEqualTo("Your AX Renewal implementation run was rejected");
    }

    @Test
    @DisplayName("an announcement opens on the user site's home, and its lock-screen sentence carries no title (ADR-098)")
    void announcementOpensHomeAndSaysNothingSpecific() {
        assertThat(NotificationType.ANNOUNCEMENT_PUBLISHED.deepLink(ORIGIN, "a1"))
                .isEqualTo("https://docs.example.com/home?announcement=a1");
        assertThat(NotificationType.ANNOUNCEMENT_PUBLISHED.body()).isEqualTo("There is a new announcement");
        assertThat(NotificationType.ANNOUNCEMENT_PUBLISHED.key()).isEqualTo("announcement.published");
    }

    private static java.util.List<String> keysFor(AppRole role) {
        return Arrays.stream(NotificationType.values()).filter(t -> t.isFor(role)).map(NotificationType::key).toList();
    }

    @Test
    @DisplayName("a normal user can receive only new-device sign-in and announcements")
    void userGetsOnlyTheTwoEveryoneTypes() {
        assertThat(keysFor(AppRole.USER)).containsExactly("auth.new-device", "announcement.published");
    }

    @Test
    @DisplayName("a developer additionally gets the four AX Renewal types, not the approval queue")
    void developerGetsAxRenewal() {
        assertThat(keysFor(AppRole.DEVELOPER))
                .containsExactly(
                        "auth.new-device",
                        "ax-renewal.member-added",
                        "ax-renewal.run-ready",
                        "ax-renewal.comment-added",
                        "ax-renewal.run-reviewed",
                        "announcement.published");
        assertThat(keysFor(AppRole.ADMIN)).isEqualTo(keysFor(AppRole.DEVELOPER));
    }

    @Test
    @DisplayName("only a super_admin gets registration.pending, since only they approve")
    void superAdminGetsEverything() {
        assertThat(keysFor(AppRole.SUPER_ADMIN)).hasSize(NotificationType.values().length);
    }
}
