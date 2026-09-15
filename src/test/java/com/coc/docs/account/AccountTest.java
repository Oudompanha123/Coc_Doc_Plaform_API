package com.coc.docs.account;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Pure unit tests of the sign-in eligibility rule ADR-014 adds. */
class AccountTest {

    private Account account(AccountStatus status, boolean deactivated) {
        return new Account(UUID.randomUUID(), "person@example.com", "Person", java.util.EnumSet.of(AppRole.USER), null, null, status, deactivated ? java.time.Instant.now() : null, java.time.Instant.now(), java.time.Instant.now(), false);
    }

    @Test
    void anActiveNonDeactivatedAccountCanSignIn() {
        assertThat(account(AccountStatus.ACTIVE, false).canSignIn()).isTrue();
    }

    @Test
    void aPendingAccountCannotSignInEvenIfNotDeactivated() {
        assertThat(account(AccountStatus.PENDING, false).canSignIn()).isFalse();
    }

    @Test
    void aRejectedAccountCannotSignIn() {
        assertThat(account(AccountStatus.REJECTED, false).canSignIn()).isFalse();
    }

    @Test
    void anActiveButDeactivatedAccountCannotSignIn() {
        assertThat(account(AccountStatus.ACTIVE, true).canSignIn()).isFalse();
    }

    @Test
    void withStatusReplacesOnlyTheStatus() {
        Account pending = account(AccountStatus.PENDING, false);

        Account approved = pending.withStatus(AccountStatus.ACTIVE);

        assertThat(approved.status()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(approved.id()).isEqualTo(pending.id());
        assertThat(approved.email()).isEqualTo(pending.email());
        assertThat(approved.role()).isEqualTo(pending.role());
        assertThat(approved.deactivatedAt()).isEqualTo(pending.deactivatedAt());
    }
}
