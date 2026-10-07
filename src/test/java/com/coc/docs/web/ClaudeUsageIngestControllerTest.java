package com.coc.docs.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.coc.docs.account.AppRole;
import com.coc.docs.auth.TestCallers;
import com.coc.docs.claudeusage.ClaudeUsageIngestTokenService;
import com.coc.docs.claudeusage.ClaudeUsageProperties;
import com.coc.docs.claudeusage.ClaudeUsageService;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * {@code POST /api/ingest}'s own authorization decision — which of the two
 * credential shapes ADR-047 accepts, if either, lets a request through. Who
 * may reach the route at all (the filter chain's {@code permitAll()}) is
 * {@link SecurityRulesTest}'s subject, not this one.
 *
 * <p>// req: FR-USAGE-002, NFR-SEC-CHANNEL, ADR-047
 */
class ClaudeUsageIngestControllerTest {

    private final ClaudeUsageService usage = mock(ClaudeUsageService.class);
    private final ClaudeUsageIngestTokenService tokens = mock(ClaudeUsageIngestTokenService.class);

    private static ApiDtos.ClaudeUsageIngestRequest request(String email) {
        return new ApiDtos.ClaudeUsageIngestRequest(
                email, "Display Name", LocalDate.of(2026, 1, 1), 100, 50, 0, 0, List.of("claude-sonnet-5"), null, null);
    }

    private ClaudeUsageIngestController controllerWithSecret(String configuredSecret) {
        return new ClaudeUsageIngestController(usage, new ClaudeUsageProperties(configuredSecret), tokens);
    }

    @Nested
    @DisplayName("shared secret (unscoped, backward-compatible)")
    class SharedSecret {

        @Test
        @DisplayName("correct secret succeeds, whatever the request's email")
        void correctSecretSucceeds() {
            when(usage.recordUsage(
                            anyString(), any(), any(), anyLong(), anyLong(), anyLong(), anyLong(), any(), any(), any()))
                    .thenReturn(new ClaudeUsageService.IngestOutcome(ClaudeUsageService.IngestOutcome.CREATED));

            ApiDtos.ClaudeUsageIngestResponse response = controllerWithSecret("s3cr3t")
                    .ingest("Bearer s3cr3t", request("anyone@example.com"));

            assertThat(response.action()).isEqualTo(ClaudeUsageService.IngestOutcome.CREATED);
            verify(tokens, never()).resolve(any());
        }

        @Test
        @DisplayName("wrong secret falls through to the token check, and is rejected when that also fails")
        void wrongSecretFallsThroughAndFails() {
            when(tokens.resolve("wrong")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> controllerWithSecret("s3cr3t").ingest("Bearer wrong", request("a@example.com")))
                    .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.UNAUTHORIZED));

            verify(usage, never()).recordUsage(any(), any(), any(), anyLong(), anyLong(), anyLong(), anyLong(), any(), any(), any());
        }
    }

    @Nested
    @DisplayName("per-account token (ADR-047) — required push-usage.mjs still works with no INGEST_SECRET configured")
    class PerAccountToken {

        @Test
        @DisplayName("no shared secret configured at all, valid token, matching email -> succeeds")
        void noSharedSecretValidTokenMatchingEmailSucceeds() {
            var owner = TestCallers.user(AppRole.ADMIN).account();
            when(tokens.resolve("coc_abc")).thenReturn(Optional.of(owner));
            when(usage.recordUsage(
                            anyString(), any(), any(), anyLong(), anyLong(), anyLong(), anyLong(), any(), any(), any()))
                    .thenReturn(new ClaudeUsageService.IngestOutcome(ClaudeUsageService.IngestOutcome.UPDATED));

            ApiDtos.ClaudeUsageIngestResponse response =
                    controllerWithSecret(null).ingest("Bearer coc_abc", request(owner.email()));

            assertThat(response.action()).isEqualTo(ClaudeUsageService.IngestOutcome.UPDATED);
        }

        @Test
        @DisplayName("a token may not report usage for an email other than its own account's")
        void tokenMayNotReportSomeoneElsesEmail() {
            var owner = TestCallers.user(AppRole.ADMIN).account();
            when(tokens.resolve("coc_abc")).thenReturn(Optional.of(owner));

            assertThatThrownBy(() -> controllerWithSecret(null).ingest("Bearer coc_abc", request("someone-else@example.com")))
                    .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.FORBIDDEN));

            verify(usage, never()).recordUsage(any(), any(), any(), anyLong(), anyLong(), anyLong(), anyLong(), any(), any(), any());
        }

        @Test
        @DisplayName("no matching token and no shared secret configured -> unauthorized, not service-unavailable")
        void noCredentialMatchesIsUnauthorized() {
            when(tokens.resolve("garbage")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> controllerWithSecret(null).ingest("Bearer garbage", request("a@example.com")))
                    .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.UNAUTHORIZED));
        }
    }

    @Nested
    @DisplayName("POST /api/ingest/scan — a scan with nothing new to upload (ADR-097)")
    class ScanOnly {

        @Test
        @DisplayName("records only the scan: no usage is written")
        void recordsTheScanAndNoUsage() {
            var owner = TestCallers.user(AppRole.ADMIN).account();
            when(tokens.resolve("coc_abc")).thenReturn(Optional.of(owner));
            when(usage.recordScan(owner.email()))
                    .thenReturn(new ClaudeUsageService.IngestOutcome(ClaudeUsageService.IngestOutcome.SCAN_RECORDED));

            ApiDtos.ClaudeUsageIngestResponse response = controllerWithSecret(null)
                    .scan("Bearer coc_abc", new ApiDtos.ClaudeUsageScanRequest(owner.email()));

            assertThat(response.action()).isEqualTo("scan-recorded");
            verify(usage, never()).recordUsage(any(), any(), any(), anyLong(), anyLong(), anyLong(), anyLong(), any(), any(), any());
        }

        @Test
        @DisplayName("same credential rules as /api/ingest: another account's email is forbidden, nothing recorded")
        void tokenMayNotRecordSomeoneElsesScan() {
            var owner = TestCallers.user(AppRole.ADMIN).account();
            when(tokens.resolve("coc_abc")).thenReturn(Optional.of(owner));

            assertThatThrownBy(() -> controllerWithSecret(null)
                            .scan("Bearer coc_abc", new ApiDtos.ClaudeUsageScanRequest("someone-else@example.com")))
                    .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.FORBIDDEN));
            verify(usage, never()).recordScan(any());
        }

        @Test
        @DisplayName("no credential -> unauthorized, nothing recorded")
        void missingCredentialIsUnauthorized() {
            assertThatThrownBy(() -> controllerWithSecret("s3cr3t").scan(null, new ApiDtos.ClaudeUsageScanRequest("a@example.com")))
                    .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.UNAUTHORIZED));
            verify(usage, never()).recordScan(any());
        }
    }

    @Test
    @DisplayName("missing Authorization header is rejected before either credential check runs")
    void missingAuthorizationHeaderIsUnauthorized() {
        assertThatThrownBy(() -> controllerWithSecret("s3cr3t").ingest(null, request("a@example.com")))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.UNAUTHORIZED));

        verify(tokens, never()).resolve(any());
        verify(usage, never()).recordUsage(any(), any(), any(), anyLong(), anyLong(), anyLong(), anyLong(), any(), any(), any());
    }
}
