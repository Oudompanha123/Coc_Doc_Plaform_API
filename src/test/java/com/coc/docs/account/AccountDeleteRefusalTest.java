package com.coc.docs.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.coc.docs.audit.AuditRepository;
import com.coc.docs.auth.AccountInviteMailer;
import com.coc.docs.auth.CurrentUser;
import com.coc.docs.auth.SessionRepository;
import com.coc.docs.auth.TestCallers;
import com.coc.docs.web.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * When the database still refuses a delete (ADR-091): the answer is a 409
 * that says what to do, never the bare "an unexpected error occurred" the
 * Users & Roles page used to show. Which references actually block a delete
 * is {@code AccountDeletionTest}'s subject, against real PostgreSQL.
 *
 * <p>// req: FR-AUTH-003, ADR-023, ADR-091
 */
class AccountDeleteRefusalTest {

    @Test
    void a_reference_the_schema_still_refuses_is_a_409_naming_deactivation_as_the_way_out() {
        AccountRepository accounts = mock(AccountRepository.class);
        AccountService service = new AccountService(
                accounts,
                mock(SessionRepository.class),
                mock(AuditRepository.class),
                new ObjectMapper(),
                mock(PasswordEncoder.class),
                mock(AccountInviteMailer.class));
        Account victim = TestCallers.user(AppRole.USER).account();
        CurrentUser admin = TestCallers.user(AppRole.SUPER_ADMIN);
        when(accounts.findById(victim.id())).thenReturn(Optional.of(victim));
        doThrow(new DataIntegrityViolationException("violates foreign key constraint \"x_fkey\""))
                .when(accounts)
                .delete(any());

        assertThatThrownBy(() -> service.delete(victim.id(), admin))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getMessage())
                            .contains(victim.email())
                            .contains("deactivate the account instead")
                            .doesNotContain("x_fkey");
                });
    }
}
