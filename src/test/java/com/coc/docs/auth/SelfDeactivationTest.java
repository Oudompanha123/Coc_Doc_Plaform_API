package com.coc.docs.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.coc.docs.PostgresTestBase;
import com.coc.docs.account.Account;
import com.coc.docs.account.AccountRepository;
import com.coc.docs.account.AccountStatus;
import com.coc.docs.account.AppRole;
import com.coc.docs.web.ApiException;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Deactivating your own account, deliberately, with a grace period (revision
 * of ADR-042) — the self-service counterpart to {@code AccountDeletionTest},
 * and the same reason it runs against a real PostgreSQL: what matters here is
 * what the database ends up with (which sessions survive, what the audit row
 * actually says, whether {@code deactivated_at} really stays null during the
 * grace period), not whether a mock's methods were called.
 *
 * <p>// req: FR-AUTH-003, FR-AUTH-007, ADR-042
 */
class SelfDeactivationTest extends PostgresTestBase {

    @Autowired SessionService sessionService;
    @Autowired AccountRepository accounts;
    @Autowired SessionRepository sessions;
    @Autowired JdbcClient jdbc;

    @BeforeEach
    void setUp() {
        jdbc.sql("truncate audit_log, app_session, account restart identity cascade").update();
    }

    private CurrentUser actorFor(Account account) {
        return new CurrentUser(account, accounts.findPermissions(account.id()), new byte[] {7});
    }

    @Test
    @DisplayName("schedules a future deactivation rather than flipping deactivated_at immediately")
    void schedulesRatherThanDeactivatesImmediately() {
        Account member = accounts.insert("member@example.com", "Member", AppRole.USER, null, AccountStatus.ACTIVE);

        Instant scheduledFor = sessionService.deactivateSelf(actorFor(member), 7);

        Account reloaded = accounts.findById(member.id()).orElseThrow();
        assertThat(reloaded.isActive()).isTrue();
        assertThat(reloaded.deactivationScheduledFor()).isEqualTo(scheduledFor);
        assertThat(scheduledFor)
                .isAfter(Instant.now().plus(Duration.ofDays(6)).plusSeconds(55))
                .isBefore(Instant.now().plus(Duration.ofDays(7)).plusSeconds(5));
    }

    @Test
    @DisplayName("refuses a grace period outside the fixed presets")
    void refusesAnOutOfRangeGracePeriod() {
        Account member = accounts.insert("member2@example.com", "Member", AppRole.USER, null, AccountStatus.ACTIVE);

        assertThatThrownBy(() -> sessionService.deactivateSelf(actorFor(member), 5))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("choose one of");
        assertThat(accounts.findById(member.id()).orElseThrow().deactivationScheduledFor())
                .isNull();
    }

    @Test
    @DisplayName("every session for the account is revoked immediately, not only the one that called it")
    void revokesEverySessionImmediately() {
        Account member = accounts.insert("multi@example.com", "Multi", AppRole.USER, null, AccountStatus.ACTIVE);
        byte[] thisDevice = {1, 2, 3};
        byte[] otherDevice = {4, 5, 6};
        sessions.insert(thisDevice, member.id(), Instant.now().plusSeconds(3600), "chrome", "127.0.0.1");
        sessions.insert(otherDevice, member.id(), Instant.now().plusSeconds(3600), "phone", "127.0.0.2");

        sessionService.deactivateSelf(
                new CurrentUser(member, accounts.findPermissions(member.id()), thisDevice), 7);

        assertThat(sessions.find(thisDevice)).isEmpty();
        assertThat(sessions.find(otherDevice)).isEmpty();
    }

    @Test
    @DisplayName("the schedule audit row names the account itself as actor and marks it self-service")
    void auditsTheScheduleAsSelfService() {
        Account member = accounts.insert("audited@example.com", "Audited", AppRole.USER, null, AccountStatus.ACTIVE);

        sessionService.deactivateSelf(actorFor(member), 14);

        String detail = jdbc.sql("select detail::text from audit_log where action = ?")
                .param(com.coc.docs.audit.AuditAction.ACCOUNT_DEACTIVATE_SCHEDULE)
                .query(String.class)
                .single();
        assertThat(detail)
                .contains("\"selfService\":true")
                .contains("\"graceDays\":14")
                .contains("audited@example.com");
    }

    @Test
    @DisplayName("signing back in during the grace period cancels the schedule and leaves the account active")
    void signInCancelsThePendingSchedule() {
        Account member = accounts.insert("comeback@example.com", "Comeback", AppRole.USER, null, AccountStatus.ACTIVE);
        sessionService.deactivateSelf(actorFor(member), 7);
        assertThat(accounts.findById(member.id()).orElseThrow().deactivationScheduledFor())
                .isNotNull();

        boolean cancelled = accounts.cancelScheduledDeactivation(member.id()) > 0;

        assertThat(cancelled).isTrue();
        Account reloaded = accounts.findById(member.id()).orElseThrow();
        assertThat(reloaded.isActive()).isTrue();
        assertThat(reloaded.deactivationScheduledFor()).isNull();
    }

    @Test
    @DisplayName("the sweep finalizes a due schedule into a real deactivation and revokes any session again")
    void sweepFinalizesADueSchedule() {
        Account member = accounts.insert("due@example.com", "Due", AppRole.USER, null, AccountStatus.ACTIVE);
        accounts.scheduleDeactivation(member.id(), Instant.now().minusSeconds(60));

        int finalized = sessionService.finalizeDueScheduledDeactivations();

        assertThat(finalized).isEqualTo(1);
        Account reloaded = accounts.findById(member.id()).orElseThrow();
        assertThat(reloaded.isActive()).isFalse();
        assertThat(reloaded.deactivationScheduledFor()).isNull();

        String detail = jdbc.sql("select detail::text from audit_log where action = ?")
                .param(com.coc.docs.audit.AuditAction.ACCOUNT_DEACTIVATE)
                .query(String.class)
                .single();
        assertThat(detail).contains("\"selfService\":true").contains("\"afterGracePeriod\":true");
    }

    @Test
    @DisplayName("the sweep leaves a not-yet-due schedule alone")
    void sweepLeavesFutureSchedulesAlone() {
        Account member = accounts.insert("notyet@example.com", "NotYet", AppRole.USER, null, AccountStatus.ACTIVE);
        accounts.scheduleDeactivation(member.id(), Instant.now().plus(Duration.ofDays(3)));

        int finalized = sessionService.finalizeDueScheduledDeactivations();

        assertThat(finalized).isZero();
        assertThat(accounts.findById(member.id()).orElseThrow().isActive()).isTrue();
    }

    @Test
    @DisplayName("a super admin with a colleague can schedule their own deactivation")
    void superAdminWithColleagueCanSelfDeactivate() {
        Account first = accounts.insert("first@example.com", "First", AppRole.SUPER_ADMIN, null, AccountStatus.ACTIVE);
        accounts.insert("second@example.com", "Second", AppRole.SUPER_ADMIN, null, AccountStatus.ACTIVE);

        sessionService.deactivateSelf(actorFor(first), 7);

        assertThat(accounts.findById(first.id()).orElseThrow().deactivationScheduledFor())
                .isNotNull();
    }

    @Test
    @DisplayName("the only active super admin cannot schedule their own deactivation")
    void loneSuperAdminIsRefused() {
        Account only =
                accounts.insert("only@example.com", "Only", AppRole.SUPER_ADMIN, null, AccountStatus.ACTIVE);

        assertThatThrownBy(() -> sessionService.deactivateSelf(actorFor(only), 7))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("only active super admin");

        Account reloaded = accounts.findById(only.id()).orElseThrow();
        assertThat(reloaded.isActive()).isTrue();
        assertThat(reloaded.deactivationScheduledFor()).isNull();
    }

    @Test
    @DisplayName("scheduling your own deactivation does not touch anyone else's account")
    void doesNotTouchOtherAccounts() {
        Account member = accounts.insert("self@example.com", "Self", AppRole.USER, null, AccountStatus.ACTIVE);
        Account other = accounts.insert("other@example.com", "Other", AppRole.USER, null, AccountStatus.ACTIVE);

        sessionService.deactivateSelf(actorFor(member), 7);

        Account reloadedOther = accounts.findById(other.id()).orElseThrow();
        assertThat(reloadedOther.isActive()).isTrue();
        assertThat(reloadedOther.deactivationScheduledFor()).isNull();
    }
}
