package com.coc.docs.claudeusage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.coc.docs.account.Account;
import com.coc.docs.account.AccountRepository;
import com.coc.docs.account.AppRole;
import com.coc.docs.audit.AuditRepository;
import com.coc.docs.auth.CurrentUser;
import com.coc.docs.auth.TestCallers;
import com.coc.docs.web.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;

/**
 * {@code claude_usage_ingest_token} minting, listing, revocation, and the
 * {@link ClaudeUsageIngestTokenService#resolve} check {@code
 * ClaudeUsageIngestController} relies on for the per-account credential
 * ADR-044 adds (V35).
 */
class ClaudeUsageIngestTokenServiceTest {

    private final ClaudeUsageIngestTokenRepository tokens = mock(ClaudeUsageIngestTokenRepository.class);
    private final AccountRepository accounts = mock(AccountRepository.class);
    private final AuditRepository audit = mock(AuditRepository.class);
    private final ClaudeUsageIngestTokenService service =
            new ClaudeUsageIngestTokenService(tokens, accounts, audit, new ObjectMapper());

    private static final CurrentUser CALLER = TestCallers.user(AppRole.ADMIN);

    private static ClaudeUsageIngestToken row(UUID accountId, String hash) {
        return new ClaudeUsageIngestToken(UUID.randomUUID(), accountId, hash, "MacBook Pro", Instant.now(), null, null);
    }

    private static String sha256Hex(String raw) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes()));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Nested
    class Create {

        @Test
        @DisplayName("mints a coc_-prefixed token, stores only its hash, and audits")
        void mintsAndStores() {
            when(tokens.insert(eq(CALLER.id()), anyString(), eq("MacBook Pro")))
                    .thenAnswer(inv -> row(CALLER.id(), inv.getArgument(1)));

            ClaudeUsageIngestTokenService.MintedToken minted = service.create(CALLER, "MacBook Pro");

            assertThat(minted.rawValue()).startsWith("coc_");
            assertThat(minted.rawValue()).hasSize(4 + 64);

            ArgumentCaptor<String> hashCaptor = ArgumentCaptor.forClass(String.class);
            verify(tokens).insert(eq(CALLER.id()), hashCaptor.capture(), eq("MacBook Pro"));
            assertThat(hashCaptor.getValue()).isEqualTo(sha256Hex(minted.rawValue()));

            verify(audit)
                    .append(eq(CALLER.id()), eq(CALLER.email()), eq("claude_usage_ingest_token.create"),
                            eq("claude_usage_ingest_token"), eq(minted.token().id()), any());
        }

        @Test
        @DisplayName("refuses a blank label without touching the repository")
        void refusesBlankLabel() {
            assertThatThrownBy(() -> service.create(CALLER, "   "))
                    .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST));

            verify(tokens, never()).insert(any(), any(), any());
        }
    }

    @Test
    @DisplayName("list() is scoped to the caller's own account")
    void listIsScopedToCaller() {
        List<ClaudeUsageIngestToken> expected = List.of(row(CALLER.id(), "hash"));
        when(tokens.listActiveByAccount(CALLER.id())).thenReturn(expected);

        assertThat(service.list(CALLER)).isSameAs(expected);
    }

    @Nested
    class Revoke {

        @Test
        @DisplayName("revokes and audits when the token belongs to the caller")
        void revokesOwnToken() {
            UUID tokenId = UUID.randomUUID();
            when(tokens.revoke(tokenId, CALLER.id())).thenReturn(1);

            service.revoke(CALLER, tokenId);

            verify(audit)
                    .append(eq(CALLER.id()), eq(CALLER.email()), eq("claude_usage_ingest_token.revoke"),
                            eq("claude_usage_ingest_token"), eq(tokenId), any());
        }

        @Test
        @DisplayName("404s rather than revoking, for a token that is not the caller's own (or already revoked)")
        void refusesSomeoneElsesToken() {
            UUID tokenId = UUID.randomUUID();
            when(tokens.revoke(tokenId, CALLER.id())).thenReturn(0);

            assertThatThrownBy(() -> service.revoke(CALLER, tokenId))
                    .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.NOT_FOUND));

            verify(audit, never()).append(any(), any(), any(), any(), any(), any());
        }
    }

    @Nested
    @DisplayName("resolve() — the check POST /api/ingest relies on")
    class Resolve {

        private final Account admin = TestCallers.user(AppRole.ADMIN).account();

        @Test
        void emptyForABlankValue() {
            assertThat(service.resolve("")).isEmpty();
            assertThat(service.resolve(null)).isEmpty();
            verify(tokens, never()).findActiveByHash(any());
        }

        @Test
        void emptyWhenNoTokenMatches() {
            when(tokens.findActiveByHash(anyString())).thenReturn(Optional.empty());

            assertThat(service.resolve("coc_nope")).isEmpty();
        }

        @Test
        void emptyWhenTheOwningAccountNoLongerExists() {
            ClaudeUsageIngestToken tokenRow = row(UUID.randomUUID(), "h");
            when(tokens.findActiveByHash(anyString())).thenReturn(Optional.of(tokenRow));
            when(accounts.findById(tokenRow.accountId())).thenReturn(Optional.empty());

            assertThat(service.resolve("coc_x")).isEmpty();
        }

        @Test
        @DisplayName("a deactivated account's token stops working immediately, with the row untouched (V35's own guarantee)")
        void emptyWhenAccountDeactivated() {
            // withStatus doesn't touch deactivatedAt; build directly to set it.
            Account deactivated = new Account(
                    admin.id(), admin.email(), admin.displayName(), admin.roles(), null, null, admin.status(),
                    Instant.now(), null, admin.createdAt(), admin.updatedAt(), false, true);
            ClaudeUsageIngestToken tokenRow = row(deactivated.id(), "h");
            when(tokens.findActiveByHash(anyString())).thenReturn(Optional.of(tokenRow));
            when(accounts.findById(deactivated.id())).thenReturn(Optional.of(deactivated));

            assertThat(service.resolve("coc_x")).isEmpty();
            verify(tokens, never()).touchLastUsed(any());
        }

        @Test
        @DisplayName("a role downgrade below ADMIN stops the token working immediately, with the row untouched")
        void emptyWhenRoleBelowAdmin() {
            Account plainUser = TestCallers.user(AppRole.USER).account();
            ClaudeUsageIngestToken tokenRow = row(plainUser.id(), "h");
            when(tokens.findActiveByHash(anyString())).thenReturn(Optional.of(tokenRow));
            when(accounts.findById(plainUser.id())).thenReturn(Optional.of(plainUser));

            assertThat(service.resolve("coc_x")).isEmpty();
            verify(tokens, never()).touchLastUsed(any());
        }

        @Test
        @DisplayName("an active admin's token resolves to that account and marks it used")
        void resolvesActiveAdmin() {
            ClaudeUsageIngestToken tokenRow = row(admin.id(), "h");
            when(tokens.findActiveByHash(anyString())).thenReturn(Optional.of(tokenRow));
            when(accounts.findById(admin.id())).thenReturn(Optional.of(admin));

            Optional<Account> resolved = service.resolve("coc_x");

            assertThat(resolved).contains(admin);
            verify(tokens).touchLastUsed(tokenRow.id());
        }

        @Test
        @DisplayName("a super_admin's token resolves too (at least ADMIN, by rank)")
        void resolvesSuperAdminByRank() {
            Account superAdmin = TestCallers.user(AppRole.SUPER_ADMIN).account();
            ClaudeUsageIngestToken tokenRow = row(superAdmin.id(), "h");
            when(tokens.findActiveByHash(anyString())).thenReturn(Optional.of(tokenRow));
            when(accounts.findById(superAdmin.id())).thenReturn(Optional.of(superAdmin));

            assertThat(service.resolve("coc_x")).contains(superAdmin);
        }
    }
}
