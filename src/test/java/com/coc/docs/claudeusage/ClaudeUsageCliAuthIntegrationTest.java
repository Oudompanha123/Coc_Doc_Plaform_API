package com.coc.docs.claudeusage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.coc.docs.PostgresTestBase;
import com.coc.docs.account.Account;
import com.coc.docs.account.AccountRepository;
import com.coc.docs.account.AccountStatus;
import com.coc.docs.account.AppRole;
import com.coc.docs.auth.CurrentUser;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * V51 against real Postgres: the atomic single-use {@code consume}, expiry,
 * and that a failed exchange still burns the code (the {@code noRollbackFor}
 * the unit test can only check the annotation for).
 *
 * <p>// req: FR-USAGE-002, NFR-SEC-CHANNEL, ADR-060
 */
class ClaudeUsageCliAuthIntegrationTest extends PostgresTestBase {

    @Autowired ClaudeUsageCliAuthService service;
    @Autowired ClaudeUsageIngestTokenService tokens;
    @Autowired AccountRepository accounts;
    @Autowired JdbcClient jdbc;

    private static final String VERIFIER = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFG";
    private Account member;

    private static String s256(String verifier) throws Exception {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
    }

    @BeforeEach
    void setUp() {
        jdbc.sql("truncate audit_log, claude_usage_cli_authorization, claude_usage_ingest_token, account restart identity cascade")
                .update();
        accounts.insert("boss@example.com", "Boss", AppRole.SUPER_ADMIN, null, AccountStatus.ACTIVE);
        member = accounts.insert("dana@example.com", "Dana", AppRole.USER, null, AccountStatus.ACTIVE);
    }

    private String approve() throws Exception {
        return service.approve(new CurrentUser(member, Set.of(), null), s256(VERIFIER), "dana-laptop");
    }

    @Test
    @DisplayName("a code exchanges once for a token that /api/ingest accepts as the approving account")
    void exchangesOnce() throws Exception {
        String code = approve();

        ClaudeUsageCliAuthService.Exchanged out = service.exchange(code, VERIFIER);

        assertThat(out.minted().rawValue()).startsWith("coc_");
        assertThat(tokens.resolve(out.minted().rawValue())).map(Account::email).contains("dana@example.com");
        assertThat(out.minted().token().label()).isEqualTo("CLI — dana-laptop");
        assertThatThrownBy(() -> service.exchange(code, VERIFIER)).hasMessageContaining("invalid_grant");
    }

    @Test
    @DisplayName("a wrong verifier burns the code for good — the right one afterwards gets nothing")
    void wrongVerifierBurnsCode() throws Exception {
        String code = approve();

        assertThatThrownBy(() -> service.exchange(code, "z".repeat(43))).hasMessageContaining("invalid_grant");
        assertThatThrownBy(() -> service.exchange(code, VERIFIER)).hasMessageContaining("invalid_grant");
        assertThat(jdbc.sql("select count(*) from claude_usage_ingest_token").query(Long.class).single()).isZero();
    }

    @Test
    @DisplayName("an expired code gets nothing")
    void expired() throws Exception {
        String code = approve();
        jdbc.sql("update claude_usage_cli_authorization set expires_at = now() - interval '1 second'").update();

        assertThatThrownBy(() -> service.exchange(code, VERIFIER)).hasMessageContaining("invalid_grant");
    }

    @Test
    @DisplayName("only the code's hash is stored")
    void storesHashOnly() throws Exception {
        String code = approve();
        assertThat(jdbc.sql("select count(*) from claude_usage_cli_authorization where code_hash = ?")
                .param(code).query(Long.class).single()).isZero();
    }
}
