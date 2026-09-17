package com.coc.docs.notify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.coc.docs.PostgresTestBase;
import com.coc.docs.account.Account;
import com.coc.docs.account.AccountRepository;
import com.coc.docs.account.AccountStatus;
import com.coc.docs.account.AppRole;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Notifications survive the thing they are about.
 *
 * <p>Against a real database because the whole claim is about rows outliving
 * state: before ADR-034 a sign-in alert was a filter over the live session
 * list, so it disappeared when that session was revoked or expired. These
 * tests are the difference.
 *
 * <p>// req: FR-AUTH-008, NFR-SEC-PII, ADR-034
 */
class NotificationPersistenceTest extends PostgresTestBase {

    @Autowired NotificationRepository notifications;
    @Autowired AccountRepository accounts;
    @Autowired JdbcClient jdbc;

    private Account dana;
    private Account sam;

    @BeforeEach
    void setUp() {
        jdbc.sql("truncate notification, audit_log, app_session, account restart identity cascade")
                .update();
        // The V4 deferred trigger keeps at least one active super_admin alive
        // across every commit in this suite.
        accounts.insert("boss@example.com", "Boss", AppRole.SUPER_ADMIN, null, AccountStatus.ACTIVE);
        dana = accounts.insert("dana@example.com", "Dana", AppRole.USER, null, AccountStatus.ACTIVE);
        sam = accounts.insert("sam@example.com", "Sam", AppRole.USER, null, AccountStatus.ACTIVE);
    }

    private void notify(Account to, NotificationType type, String targetId) {
        notifications.insertFor(List.of(to.id()), type, targetId, null);
    }

    @Test
    @DisplayName("one row per recipient, so five approvers can read it at five different times")
    void fansOutPerRecipient() {
        int written = notifications.insertFor(
                Set.of(dana.id(), sam.id()), NotificationType.NEW_REGISTRATION, "applicant-1", null);

        assertThat(written).isEqualTo(2);
        assertThat(notifications.countUnread(dana.id())).isEqualTo(1);
        assertThat(notifications.countUnread(sam.id())).isEqualTo(1);
    }

    @Test
    @DisplayName("reading one person's copy leaves everybody else's unread")
    void readIsPerRecipient() {
        notifications.insertFor(
                Set.of(dana.id(), sam.id()), NotificationType.NEW_REGISTRATION, "applicant-1", null);
        var danas = notifications.findFor(dana.id(), 10).getFirst();

        notifications.markRead(danas.id(), dana.id(), Instant.now());

        // The point of one row each: "read" is a property of the pair, and
        // nobody else's bell changes because Dana looked at hers.
        assertThat(notifications.countUnread(dana.id())).isZero();
        assertThat(notifications.countUnread(sam.id())).isEqualTo(1);
    }

    @Test
    @DisplayName("the alert outlives the session it is about")
    void survivesTheSessionItDescribes() {
        notify(dana, NotificationType.NEW_DEVICE_SIGN_IN, "some-session-id");

        // Exactly what used to erase it: the session ends. Deriving the bell
        // from live sessions meant the record of a 3am sign-in was gone by
        // morning — the one case the alert exists for.
        jdbc.sql("delete from app_session").update();

        assertThat(notifications.findFor(dana.id(), 10)).hasSize(1);
        assertThat(notifications.countUnread(dana.id())).isEqualTo(1);
    }

    @Test
    @DisplayName("nobody can mark somebody else's notification read")
    void cannotMarkAnotherPersonsRead() {
        notify(dana, NotificationType.NEW_DEVICE_SIGN_IN, "s1");
        var danas = notifications.findFor(dana.id(), 10).getFirst();

        boolean marked = notifications.markRead(danas.id(), sam.id(), Instant.now());

        // The account id is part of the statement, so this is not a check that
        // could be forgotten — it cannot be expressed.
        assertThat(marked).isFalse();
        assertThat(notifications.countUnread(dana.id())).isEqualTo(1);
    }

    @Test
    @DisplayName("a second read does not move the timestamp")
    void readAtRecordsTheFirstLook() {
        notify(dana, NotificationType.NEW_DEVICE_SIGN_IN, "s1");
        var row = notifications.findFor(dana.id(), 10).getFirst();
        Instant first = Instant.now().minusSeconds(600);
        notifications.markRead(row.id(), dana.id(), first);

        boolean again = notifications.markRead(row.id(), dana.id(), Instant.now());

        assertThat(again).isFalse();
        assertThat(notifications.findFor(dana.id(), 10).getFirst().readAt())
                .isCloseTo(first, within(1, java.time.temporal.ChronoUnit.SECONDS));
    }

    @Test
    @DisplayName("newest first, so the bell opens on what just happened")
    void newestFirst() {
        notify(dana, NotificationType.NEW_REGISTRATION, "first");
        notify(dana, NotificationType.NEW_DEVICE_SIGN_IN, "second");

        assertThat(notifications.findFor(dana.id(), 10))
                .extracting(Notification::targetId)
                .containsExactly("second", "first");
    }

    @Test
    @DisplayName("one person's notifications are never another's")
    void inboxesDoNotLeak() {
        notify(dana, NotificationType.NEW_DEVICE_SIGN_IN, "danas-session");

        assertThat(notifications.findFor(sam.id(), 10)).isEmpty();
        assertThat(notifications.countUnread(sam.id())).isZero();
    }

    @Test
    @DisplayName("deleting an account takes its notifications with it")
    void cascadesWithTheAccount() {
        notify(dana, NotificationType.NEW_DEVICE_SIGN_IN, "s1");

        accounts.delete(dana.id());

        Long left = jdbc.sql("select count(*) from notification").query(Long.class).single();
        assertThat(left).isZero();
    }

    @Test
    @DisplayName("the sweep drops old rows, read or not, and keeps recent ones")
    void retentionDropsOldRows() {
        notify(dana, NotificationType.NEW_DEVICE_SIGN_IN, "old-unread");
        notify(dana, NotificationType.NEW_DEVICE_SIGN_IN, "recent");
        jdbc.sql("update notification set created_at = now() - interval '100 days' where target_id = 'old-unread'")
                .update();

        int removed = notifications.deleteOlderThan(Instant.now().minus(NotificationMaintenance.KEEP_FOR));

        // Unread included on purpose: keeping them for ever would let one
        // ignored notification set the retention policy.
        assertThat(removed).isEqualTo(1);
        assertThat(notifications.findFor(dana.id(), 10))
                .extracting(Notification::targetId)
                .containsExactly("recent");
    }
}
