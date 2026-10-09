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
import com.coc.docs.claudeusage.ClaudeUsageScanLogService;
import com.coc.docs.claudeusage.ClaudeUsageService;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
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
    private final ClaudeUsageScanLogService scanLogs = mock(ClaudeUsageScanLogService.class);

    private static ApiDtos.ClaudeUsageIngestRequest request(String email) {
        return new ApiDtos.ClaudeUsageIngestRequest(
                email, "Display Name", LocalDate.of(2026, 1, 1), 100, 50, 0, 0, List.of("claude-sonnet-5"), null, null);
    }

    private ClaudeUsageIngestController controllerWithSecret(String configuredSecret) {
        return new ClaudeUsageIngestController(usage, new ClaudeUsageProperties(configuredSecret), tokens, scanLogs);
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

    @Nested
    @DisplayName("POST /api/ingest/scan-log — one scan's record for View Logs (ADR-099)")
    class ScanLog {

        private ApiDtos.ClaudeUsageScanLogRequest log(String email) {
            Instant start = Instant.parse("2026-10-07T04:00:01Z");
            return new ApiDtos.ClaudeUsageScanLogRequest(
                    UUID.randomUUID(), email, "scheduled", "0.4.0", start, start.plusSeconds(8),
                    118, 127, 163, 15_240L, 1, "uploaded", List.of(), null, null);
        }

        @Test
        @DisplayName("own token, own email -> recorded, and no usage or scan time is written")
        void recordsTheLogOnly() {
            var owner = TestCallers.user(AppRole.ADMIN).account();
            when(tokens.resolve("coc_abc")).thenReturn(Optional.of(owner));
            when(scanLogs.record(any())).thenReturn(ClaudeUsageScanLogService.RECORDED);

            ApiDtos.ClaudeUsageIngestResponse response = controllerWithSecret(null).scanLog("Bearer coc_abc", log(owner.email()));

            assertThat(response.action()).isEqualTo("recorded");
            verify(usage, never()).recordScan(any());
            verify(usage, never()).recordUsage(any(), any(), any(), anyLong(), anyLong(), anyLong(), anyLong(), any(), any(), any());
        }

        @Test
        @DisplayName("another account's email is forbidden, nothing recorded")
        void tokenMayNotLogSomeoneElsesScan() {
            var owner = TestCallers.user(AppRole.ADMIN).account();
            when(tokens.resolve("coc_abc")).thenReturn(Optional.of(owner));

            assertThatThrownBy(() -> controllerWithSecret(null).scanLog("Bearer coc_abc", log("someone-else@example.com")))
                    .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.FORBIDDEN));
            verify(scanLogs, never()).record(any());
        }

        @Test
        @DisplayName("no credential -> unauthorized, nothing recorded")
        void missingCredentialIsUnauthorized() {
            assertThatThrownBy(() -> controllerWithSecret("s3cr3t").scanLog(null, log("a@example.com")))
                    .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.UNAUTHORIZED));
            verify(scanLogs, never()).record(any());
        }
    }

    /**
     * A disabled member's reporter is refused on every ingest route (ADR-105),
     * whichever credential it holds. Wired with the real services over mocked
     * repositories, so this proves the refusal itself rather than a mock's.
     */
    @Nested
    @DisplayName("a disabled member is refused on every ingest route (ADR-105)")
    class DisabledMember {

        private final com.coc.docs.claudeusage.ClaudeUsageMemberRepository members =
                mock(com.coc.docs.claudeusage.ClaudeUsageMemberRepository.class);
        private final com.coc.docs.claudeusage.ClaudeUsageDailyRepository daily =
                mock(com.coc.docs.claudeusage.ClaudeUsageDailyRepository.class);
        private final com.coc.docs.claudeusage.ClaudeUsageScanLogRepository logs =
                mock(com.coc.docs.claudeusage.ClaudeUsageScanLogRepository.class);
        private final com.coc.docs.claudeusage.InMemoryTrackingPauses pauses =
                new com.coc.docs.claudeusage.InMemoryTrackingPauses();
        private final ClaudeUsageService realUsage = new ClaudeUsageService(
                members,
                daily,
                mock(com.coc.docs.claudeusage.ClaudeUsageDailyModelRepository.class),
                mock(com.coc.docs.claudeusage.ClaudeUsageActivityRepository.class),
                mock(com.coc.docs.audit.AuditRepository.class),
                new com.fasterxml.jackson.databind.ObjectMapper(),
                pauses);
        private final ClaudeUsageScanLogService realScanLogs = new ClaudeUsageScanLogService(
                logs,
                members,
                com.fasterxml.jackson.databind.json.JsonMapper.builder().findAndAddModules().build(),
                java.time.Clock.systemUTC(),
                pauses);
        private final com.coc.docs.account.Account owner = TestCallers.user(AppRole.USER).account();

        private ClaudeUsageIngestController controller(String sharedSecret) {
            return new ClaudeUsageIngestController(realUsage, new ClaudeUsageProperties(sharedSecret), tokens, realScanLogs);
        }

        private void memberIs(String status) {
            when(members.findByEmail(owner.email().toLowerCase())).thenReturn(Optional.of(new com.coc.docs.claudeusage.ClaudeUsageMember(
                    UUID.randomUUID(), owner.email().toLowerCase(), "Owner", "member", status, null, null, Instant.now(), Instant.now())));
        }

        private ApiDtos.ClaudeUsageScanLogRequest log() {
            Instant start = Instant.now().minusSeconds(30);
            return new ApiDtos.ClaudeUsageScanLogRequest(
                    UUID.randomUUID(), owner.email(), "scheduled", "0.4.0", start, start.plusSeconds(8),
                    1, 1, 0, 0L, 0, "not-needed", List.of(), null, null);
        }

        private static void isTrackingDisabled(Throwable e) {
            assertThat(e).isInstanceOfSatisfying(ApiException.class, api -> {
                assertThat(api.status()).isEqualTo(HttpStatus.FORBIDDEN);
                assertThat(api.getMessage()).isEqualTo(ClaudeUsageService.TRACKING_DISABLED);
            });
        }

        @Test
        @DisplayName("with their own per-account token: /api/ingest, /scan and /scan-log all 403, nothing written")
        void ownTokenRefusedEverywhere() {
            memberIs(com.coc.docs.claudeusage.ClaudeUsageMember.STATUS_REMOVED);
            when(tokens.resolve("coc_own")).thenReturn(Optional.of(owner));
            ClaudeUsageIngestController controller = controller(null);

            assertThatThrownBy(() -> controller.ingest("Bearer coc_own", request(owner.email())))
                    .satisfies(DisabledMember::isTrackingDisabled);
            assertThatThrownBy(() -> controller.scan("Bearer coc_own", new ApiDtos.ClaudeUsageScanRequest(owner.email())))
                    .satisfies(DisabledMember::isTrackingDisabled);
            assertThatThrownBy(() -> controller.scanLog("Bearer coc_own", log()))
                    .satisfies(DisabledMember::isTrackingDisabled);

            verify(daily, never()).upsert(any(), any(), anyLong(), anyLong(), anyLong(), anyLong(), any(), any());
            verify(members, never()).touchLastScan(any());
            verify(members, never()).updateStatus(any(), any());
            verify(logs, never()).insert(any(), anyString());
        }

        @Test
        @DisplayName("the unscoped shared secret cannot bypass it either")
        void sharedSecretRefusedEverywhere() {
            memberIs(com.coc.docs.claudeusage.ClaudeUsageMember.STATUS_REMOVED);
            ClaudeUsageIngestController controller = controller("s3cr3t");

            assertThatThrownBy(() -> controller.ingest("Bearer s3cr3t", request(owner.email())))
                    .satisfies(DisabledMember::isTrackingDisabled);
            assertThatThrownBy(() -> controller.scan("Bearer s3cr3t", new ApiDtos.ClaudeUsageScanRequest(owner.email())))
                    .satisfies(DisabledMember::isTrackingDisabled);
            assertThatThrownBy(() -> controller.scanLog("Bearer s3cr3t", log()))
                    .satisfies(DisabledMember::isTrackingDisabled);

            verify(daily, never()).upsert(any(), any(), anyLong(), anyLong(), anyLong(), anyLong(), any(), any());
            verify(logs, never()).insert(any(), anyString());
        }

        @Test
        @DisplayName("a bad credential is still 401 for a disabled member -- the credential is checked first")
        void credentialStillCheckedFirst() {
            memberIs(com.coc.docs.claudeusage.ClaudeUsageMember.STATUS_REMOVED);
            when(tokens.resolve("garbage")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> controller(null).ingest("Bearer garbage", request(owner.email())))
                    .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.UNAUTHORIZED));
        }

        @Test
        @DisplayName("enabled again: a retried report from the disabled period is 409 disabled-period on /api/ingest and /scan-log")
        void disabledPeriodRetriesRefusedAfterEnable() {
            memberIs(com.coc.docs.claudeusage.ClaudeUsageMember.STATUS_ACTIVE);
            when(tokens.resolve("coc_own")).thenReturn(Optional.of(owner));
            UUID memberId = members.findByEmail(owner.email().toLowerCase()).orElseThrow().id();
            // Disabled until a minute ago, from two days back.
            pauses.add(memberId, Instant.now().minus(java.time.Duration.ofDays(2)), Instant.now().minusSeconds(60));
            ClaudeUsageIngestController controller = controller(null);
            LocalDate yesterday = LocalDate.now(java.time.ZoneId.of("Asia/Phnom_Penh")).minusDays(1);

            assertThatThrownBy(() -> controller.ingest("Bearer coc_own", new ApiDtos.ClaudeUsageIngestRequest(
                            owner.email(), "Owner", yesterday, 100, 50, 0, 0, List.of(), null, null)))
                    .isInstanceOfSatisfying(ApiException.class, e -> {
                        assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                        assertThat(e.code()).isEqualTo(ClaudeUsageService.CODE_DISABLED_PERIOD);
                    });
            Instant during = Instant.now().minus(java.time.Duration.ofDays(1));
            assertThatThrownBy(() -> controller.scanLog("Bearer coc_own", new ApiDtos.ClaudeUsageScanLogRequest(
                            UUID.randomUUID(), owner.email(), "scheduled", "0.4.0", during, during.plusSeconds(8),
                            1, 1, 0, 0L, 0, "not-needed", List.of(), null, null)))
                    .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ClaudeUsageService.CODE_DISABLED_PERIOD));

            verify(daily, never()).upsert(any(), any(), anyLong(), anyLong(), anyLong(), anyLong(), any(), any());
            verify(logs, never()).insert(any(), anyString());
        }

        @Test
        @DisplayName("the problem body carries the machine-readable code the CLI matches on")
        void problemBodyCarriesCode() {
            var problem = new ApiExceptionHandler().handleApi(ApiException.withCode(
                    HttpStatus.FORBIDDEN, ClaudeUsageService.CODE_TRACKING_DISABLED, ClaudeUsageService.TRACKING_DISABLED));

            assertThat(problem.getStatus()).isEqualTo(403);
            assertThat(problem.getDetail()).isEqualTo(ClaudeUsageService.TRACKING_DISABLED);
            assertThat(problem.getProperties()).containsEntry("code", "tracking-disabled");
            assertThat(new ApiExceptionHandler().handleApi(ApiException.forbidden("x")).getProperties())
                    .as("no code for an ordinary error")
                    .isNull();
        }

        @Test
        @DisplayName("enabled again: the same credential is accepted on all three routes")
        void acceptedAfterEnable() {
            memberIs(com.coc.docs.claudeusage.ClaudeUsageMember.STATUS_ACTIVE);
            when(tokens.resolve("coc_own")).thenReturn(Optional.of(owner));
            when(daily.upsert(any(), any(), anyLong(), anyLong(), anyLong(), anyLong(), any(), any())).thenReturn(true);
            when(logs.insert(any(), anyString())).thenReturn(true);
            ClaudeUsageIngestController controller = controller(null);

            assertThat(controller.ingest("Bearer coc_own", request(owner.email())).action())
                    .isEqualTo(ClaudeUsageService.IngestOutcome.CREATED);
            assertThat(controller.scan("Bearer coc_own", new ApiDtos.ClaudeUsageScanRequest(owner.email())).action())
                    .isEqualTo(ClaudeUsageService.IngestOutcome.SCAN_RECORDED);
            assertThat(controller.scanLog("Bearer coc_own", log()).action()).isEqualTo(ClaudeUsageScanLogService.RECORDED);
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
