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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;

/**
 * The CLI browser login's approve → exchange handshake (ADR-060): a one-time
 * code bound to a PKCE S256 challenge, exchanged for an ordinary V35 token.
 *
 * <p>// req: FR-USAGE-002, NFR-SEC-CHANNEL, ADR-060
 */
class ClaudeUsageCliAuthServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-29T03:00:00Z");
    private static final String VERIFIER = "v".repeat(43);
    private static final String CHALLENGE = s256(VERIFIER);

    private final ClaudeUsageCliAuthorizationRepository codes = mock(ClaudeUsageCliAuthorizationRepository.class);
    private final ClaudeUsageIngestTokenService tokens = mock(ClaudeUsageIngestTokenService.class);
    private final AccountRepository accounts = mock(AccountRepository.class);
    private final AuditRepository audit = mock(AuditRepository.class);
    private final ClaudeUsageCliAuthService service = new ClaudeUsageCliAuthService(
            codes, tokens, accounts, audit, new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC));

    private static final CurrentUser CALLER = TestCallers.user(AppRole.USER);

    private static String s256(String verifier) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(d);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String sha256Hex(String raw) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static ClaudeUsageIngestTokenService.MintedToken minted(UUID accountId) {
        return new ClaudeUsageIngestTokenService.MintedToken(
                new ClaudeUsageIngestToken(UUID.randomUUID(), accountId, "h", "CLI — laptop", NOW, null, null),
                "coc_fixture"); // gitleaks:allow — fixture, not a credential
    }

    @Nested
    class Approve {

        @Test
        @DisplayName("stores only the code's hash, bound to the caller and challenge, for two minutes, and audits")
        void storesHashNotCode() {
            when(codes.insert(any(), anyString(), anyString(), anyString(), any())).thenReturn(UUID.randomUUID());

            String code = service.approve(CALLER, CHALLENGE, "dana-laptop");

            assertThat(code).matches("^[A-Za-z0-9_-]{43}$");
            ArgumentCaptor<String> hash = ArgumentCaptor.forClass(String.class);
            verify(codes).insert(eq(CALLER.id()), hash.capture(), eq(CHALLENGE), eq("dana-laptop"),
                    eq(NOW.plus(ClaudeUsageCliAuthService.CODE_TTL)));
            assertThat(hash.getValue()).isEqualTo(sha256Hex(code)).isNotEqualTo(code);
            verify(audit).append(eq(CALLER.id()), eq(CALLER.email()), eq("claude_usage_cli_authorization.approve"),
                    eq("claude_usage_cli_authorization"), any(), any());
        }

        @Test
        @DisplayName("refuses a challenge that is not a base64url SHA-256 digest (e.g. a plain-method challenge)")
        void refusesBadChallenge() {
            assertThatThrownBy(() -> service.approve(CALLER, "short", "x"))
                    .isInstanceOf(ApiException.class)
                    .extracting(e -> ((ApiException) e).status()).isEqualTo(HttpStatus.BAD_REQUEST);
            verify(codes, never()).insert(any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("sanitizes the untrusted device name: control characters dropped, capped at 60")
        void sanitizesDevice() {
            assertThat(ClaudeUsageCliAuthService.cleanDevice("lap\u0000top\n")).isEqualTo("laptop");
            assertThat(ClaudeUsageCliAuthService.cleanDevice("  ")).isEqualTo("unnamed machine");
            assertThat(ClaudeUsageCliAuthService.cleanDevice("x".repeat(100))).hasSize(60);
        }
    }

    @Nested
    class Exchange {

        private final Account user = CALLER.account();
        private final String code = "c".repeat(43);

        @Test
        @DisplayName("the right verifier gets an ordinary V35 token for the approving account")
        void mintsForTheRightVerifier() {
            when(codes.consume(sha256Hex(code)))
                    .thenReturn(Optional.of(new ClaudeUsageCliAuthorizationRepository.ConsumedCode(user.id(), CHALLENGE, "laptop")));
            when(accounts.findById(user.id())).thenReturn(Optional.of(user));
            when(tokens.createFor(user, "CLI — laptop")).thenReturn(minted(user.id()));

            ClaudeUsageCliAuthService.Exchanged out = service.exchange(code, VERIFIER);

            assertThat(out.minted().rawValue()).isEqualTo("coc_fixture");
            assertThat(out.account()).isEqualTo(user);
        }

        @Test
        @DisplayName("a wrong verifier gets nothing — and the code was consumed before the check, so it cannot be retried")
        void wrongVerifier() {
            when(codes.consume(sha256Hex(code)))
                    .thenReturn(Optional.of(new ClaudeUsageCliAuthorizationRepository.ConsumedCode(user.id(), CHALLENGE, "laptop")));

            assertThatThrownBy(() -> service.exchange(code, "w".repeat(43))).hasMessageContaining("invalid_grant");
            verify(codes).consume(sha256Hex(code));
            verify(tokens, never()).createFor(any(), any());
        }

        @Test
        @DisplayName("an unknown, expired, or already-used code (consume finds nothing) gets the same invalid_grant")
        void unusableCode() {
            when(codes.consume(anyString())).thenReturn(Optional.empty());
            assertThatThrownBy(() -> service.exchange(code, VERIFIER)).hasMessageContaining("invalid_grant");
            verify(tokens, never()).createFor(any(), any());
        }

        @Test
        @DisplayName("an account deactivated or downgraded between approval and exchange gets nothing")
        void accountNoLongerEligible() {
            Account guest = TestCallers.user(AppRole.GUEST).account();
            when(codes.consume(sha256Hex(code)))
                    .thenReturn(Optional.of(new ClaudeUsageCliAuthorizationRepository.ConsumedCode(guest.id(), CHALLENGE, "laptop")));
            when(accounts.findById(guest.id())).thenReturn(Optional.of(guest));

            assertThatThrownBy(() -> service.exchange(code, VERIFIER)).hasMessageContaining("invalid_grant");
            verify(tokens, never()).createFor(any(), any());
        }

        @Test
        @DisplayName("malformed input is refused before any database work")
        void malformed() {
            assertThatThrownBy(() -> service.exchange("x", VERIFIER)).hasMessageContaining("invalid_grant");
            assertThatThrownBy(() -> service.exchange(code, "short")).hasMessageContaining("invalid_grant");
            verify(codes, never()).consume(any());
        }

        @Test
        @DisplayName("a failed exchange does not roll back — the consumed code stays consumed")
        void noRollbackOnFailure() throws Exception {
            Transactional tx = ClaudeUsageCliAuthService.class
                    .getMethod("exchange", String.class, String.class)
                    .getAnnotation(Transactional.class);
            assertThat(tx.noRollbackFor()).contains(ApiException.class);
        }
    }
}
