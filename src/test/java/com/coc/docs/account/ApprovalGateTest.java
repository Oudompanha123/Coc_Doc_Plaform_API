package com.coc.docs.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.coc.docs.PostgresTestBase;
import com.coc.docs.auth.CurrentUser;
import com.coc.docs.web.ApiException;
import java.util.EnumSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The approval gate (ADR-017): signing in stops being the same thing as being
 * let in.
 *
 * <p>Against a real database rather than mocks, because every claim here is
 * about a status literal in an INSERT, a partial index, or a constraint —
 * none of which a mocked repository can get wrong or right.
 *
 * <p>// req: FR-AUTH-002, FR-AUTH-003, FR-AUTH-005, FR-AUTH-008, ADR-017
 */
class ApprovalGateTest extends PostgresTestBase {

    @Autowired AccountRepository accounts;
    @Autowired AccountService accountService;
    @Autowired JdbcClient jdbc;

    @BeforeEach
    void clear() {
        jdbc.sql("truncate audit_log, app_session, account restart identity cascade").update();
    }

    /** A super admin to act as, created the way a super admin is: active, deliberately. */
    private CurrentUser superAdmin() {
        Account admin = accounts.insert(
                "boss@example.com", "Boss", AppRole.SUPER_ADMIN, null, AccountStatus.ACTIVE);
        return new CurrentUser(admin, accounts.findPermissions(admin.id()), new byte[] {9});
    }

    @Test
    @DisplayName("an applicant lands pending, at a role that grants nothing")
    void applicationIsPendingAndPowerless() {
        Account applied = accounts.insertApplication("new@example.com", "New", null, null);

        assertThat(applied.status()).isEqualTo(AccountStatus.PENDING);
        assertThat(applied.roles()).containsExactly(AppRole.GUEST);
        // The whole gate in one assertion: the row exists and opens nothing.
        assertThat(applied.canSignIn()).isFalse();
        assertThat(accounts.findPermissions(applied.id())).isEmpty();
    }

    @Test
    @DisplayName("both doors lead to the same queue")
    void passwordRegistrationIsGatedToo() {
        // Before ADR-017 this produced an active account instantly, which meant
        // the Google gate had an open door beside it.
        Account registered = accountService.register("pw@example.com", "PW", "$2a$10$nothashedhere");

        assertThat(registered.status()).isEqualTo(AccountStatus.PENDING);
        assertThat(registered.canSignIn()).isFalse();
        assertThat(accounts.findByStatus(AccountStatus.PENDING))
                .extracting(Account::email)
                .contains("pw@example.com");
    }

    @Test
    @DisplayName("approving admits them at the role chosen in the same decision")
    void approveCarriesTheRole() {
        CurrentUser actor = superAdmin();
        Account applied = accounts.insertApplication("editor@example.com", "Editor", null, null);

        Account approved = accountService.approve(applied.id(), AppRole.ADMIN, actor);

        assertThat(approved.status()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(approved.canSignIn()).isTrue();
        // guest is replaced, not joined: holding both would leave a role that
        // grants nothing attached to someone who has been let in.
        assertThat(approved.roles()).isEqualTo(EnumSet.of(AppRole.ADMIN));
    }

    @Test
    @DisplayName("approving at guest is refused, because it would admit nobody")
    void approvingAtGuestIsRefused() {
        CurrentUser actor = superAdmin();
        Account applied = accounts.insertApplication("nobody@example.com", "Nobody", null, null);

        assertThatThrownBy(() -> accountService.approve(applied.id(), AppRole.GUEST, actor))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("guest grants nothing");

        assertThat(accounts.findById(applied.id()).orElseThrow().status())
                .isEqualTo(AccountStatus.PENDING);
    }

    @Test
    @DisplayName("rejecting keeps the row, so the trail still answers who was turned down")
    void rejectionIsRecordedNotErased() {
        CurrentUser actor = superAdmin();
        Account applied = accounts.insertApplication("no@example.com", "No", null, null);

        Account rejected = accountService.reject(applied.id(), actor);

        assertThat(rejected.status()).isEqualTo(AccountStatus.REJECTED);
        assertThat(rejected.canSignIn()).isFalse();
        assertThat(accounts.findByEmail("no@example.com")).isPresent();
    }

    @Test
    @DisplayName("a decision can only be made once")
    void decidingTwiceIsRefused() {
        CurrentUser actor = superAdmin();
        Account applied = accounts.insertApplication("twice@example.com", "Twice", null, null);
        accountService.approve(applied.id(), AppRole.USER, actor);

        // Re-approving an active account is an account change and belongs on
        // the PATCH path, where it is audited as one.
        assertThatThrownBy(() -> accountService.approve(applied.id(), AppRole.ADMIN, actor))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("not pending");
    }

    @Test
    @DisplayName("an account created by a super admin skips the queue")
    void adminCreatedAccountsAreActive() {
        CurrentUser actor = superAdmin();

        Account created = accountService.create("invited@example.com", AppRole.ADMIN, actor);

        // The super admin creating it is the approval; queuing it would ask
        // them to approve their own decision.
        assertThat(created.status()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(created.canSignIn()).isTrue();
    }

    @Test
    @DisplayName("isEmpty answers only for a genuinely empty installation")
    void isEmptyIsNarrow() {
        assertThat(accounts.isEmpty()).isTrue();

        accounts.insertApplication("someone@example.com", "Someone", null, null);

        // A pending applicant still counts. Otherwise the next person to sign
        // in after a wave of unapproved applications would be bootstrapped
        // straight to super_admin.
        assertThat(accounts.isEmpty()).isFalse();
    }
}
