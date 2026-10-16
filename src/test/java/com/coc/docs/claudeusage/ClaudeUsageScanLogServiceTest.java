package com.coc.docs.claudeusage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.coc.docs.web.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;

/**
 * The scan log's own rules (ADR-099): status derived from steps, "no new
 * usage" is success, steps normalized to the fixed catalogue, secrets
 * redacted, resends ignored, and the admin date range.
 *
 * <p>// req: FR-USAGE-002, ADR-099
 */
class ClaudeUsageScanLogServiceTest {

    /** 2026-10-07 12:00 in Phnom Penh. */
    private static final Instant NOW = Instant.parse("2026-10-07T05:00:00Z");

    private final ClaudeUsageScanLogRepository logs = mock(ClaudeUsageScanLogRepository.class);
    private final ClaudeUsageMemberRepository members = mock(ClaudeUsageMemberRepository.class);
    private final InMemoryTrackingPauses pauses = new InMemoryTrackingPauses();
    private final ObjectMapper json = JsonMapper.builder().findAndAddModules().build();
    private final ClaudeUsageScanLogService service =
            new ClaudeUsageScanLogService(logs, members, json, Clock.fixed(NOW, ZoneOffset.UTC), pauses);

    private static ClaudeUsageScanLog.Step step(String key, String status, String detail) {
        return new ClaudeUsageScanLog.Step(key, status, NOW.minusSeconds(5), detail);
    }

    /** Every step successful, as the CLI sends an ordinary upload. */
    private static List<ClaudeUsageScanLog.Step> allSuccessful() {
        List<ClaudeUsageScanLog.Step> steps = new ArrayList<>();
        for (String key : ClaudeUsageScanLogService.STEPS) {
            steps.add(step(key, "success", key + " ok"));
        }
        return steps;
    }

    private static ClaudeUsageScanLogService.Report report(List<ClaudeUsageScanLog.Step> steps, String uploadStatus, String errorStep, String error) {
        return new ClaudeUsageScanLogService.Report(
                UUID.randomUUID(), " Dana@Example.com ", "scheduled", "0.4.0", NOW.minusSeconds(8), NOW,
                118, 127, 163, 15_240L, 1, uploadStatus, steps, errorStep, error);
    }

    private ClaudeUsageScanLog stored() {
        ArgumentCaptor<ClaudeUsageScanLog> log = ArgumentCaptor.forClass(ClaudeUsageScanLog.class);
        verify(logs).insert(log.capture(), anyString());
        return log.getValue();
    }

    private String storedSteps() {
        ArgumentCaptor<String> steps = ArgumentCaptor.forClass(String.class);
        verify(logs).insert(any(), steps.capture());
        return steps.getValue();
    }

    @Nested
    @DisplayName("record")
    class Record {

        @Test
        @DisplayName("after Enable, a scan log from while disabled is refused for good (409 disabled-period); a later one is stored")
        void disabledPeriodScanLogIsRefused() {
            UUID memberId = UUID.randomUUID();
            when(members.findByEmail("dana@example.com")).thenReturn(Optional.of(new ClaudeUsageMember(
                    memberId, "dana@example.com", "Dana", "member", ClaudeUsageMember.STATUS_ACTIVE, null, null, NOW, NOW)));
            when(logs.insert(any(), anyString())).thenReturn(true);

            // The report starts at NOW - 8s. A window covering it: refused.
            pauses.add(memberId, NOW.minusSeconds(3600), NOW);
            assertThatThrownBy(() -> service.record(report(allSuccessful(), "uploaded", null, null)))
                    .isInstanceOfSatisfying(ApiException.class, e -> {
                        assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                        assertThat(e.code()).isEqualTo(ClaudeUsageService.CODE_DISABLED_PERIOD);
                    });
            verify(logs, never()).insert(any(), anyString());
        }

        @Test
        @DisplayName("a scan log that started after the resume moment is stored")
        void scanLogAfterResumeIsStored() {
            UUID memberId = UUID.randomUUID();
            when(members.findByEmail("dana@example.com")).thenReturn(Optional.of(new ClaudeUsageMember(
                    memberId, "dana@example.com", "Dana", "member", ClaudeUsageMember.STATUS_ACTIVE, null, null, NOW, NOW)));
            when(logs.insert(any(), anyString())).thenReturn(true);
            pauses.add(memberId, NOW.minusSeconds(3600), NOW.minusSeconds(60));

            assertThat(service.record(report(allSuccessful(), "uploaded", null, null)))
                    .isEqualTo(ClaudeUsageScanLogService.RECORDED);
        }

        @Test
        @DisplayName("a disabled member's scan log is refused with 403 and not stored (ADR-105)")
        void refusesDisabledMember() {
            when(members.findByEmail("dana@example.com")).thenReturn(Optional.of(new ClaudeUsageMember(
                    UUID.randomUUID(), "dana@example.com", "Dana", "member", ClaudeUsageMember.STATUS_REMOVED, null, null, NOW, NOW)));

            assertThatThrownBy(() -> service.record(report(allSuccessful(), "uploaded", null, null)))
                    .isInstanceOfSatisfying(ApiException.class, e -> {
                        assertThat(e.status()).isEqualTo(HttpStatus.FORBIDDEN);
                        assertThat(e.getMessage()).isEqualTo(ClaudeUsageService.TRACKING_DISABLED);
                    });
            verify(logs, never()).insert(any(), anyString());
        }

        @Test
        @DisplayName("an upload with every step successful is a success, matched to the roster by normalized email")
        void successfulUpload() {
            UUID memberId = UUID.randomUUID();
            when(members.findByEmail("dana@example.com")).thenReturn(Optional.of(new ClaudeUsageMember(
                    memberId, "dana@example.com", "Dana", "member", "active", null, null, NOW, NOW)));
            when(logs.insert(any(), anyString())).thenReturn(true);

            assertThat(service.record(report(allSuccessful(), "uploaded", null, null))).isEqualTo("recorded");

            ClaudeUsageScanLog log = stored();
            assertThat(log.status()).isEqualTo("success");
            assertThat(log.memberId()).isEqualTo(memberId);
            assertThat(log.email()).isEqualTo("dana@example.com");
            assertThat(log.trigger()).isEqualTo("scheduled");
            assertThat(log.receivedAt()).isEqualTo(NOW);
            assertThat(log.durationMs()).isEqualTo(8_000L);
        }

        @Test
        @DisplayName("no new usage is a successful scan, not a warning")
        void noNewUsageIsSuccess() {
            List<ClaudeUsageScanLog.Step> steps = allSuccessful();
            steps.set(5, step("check-new-usage", "success", "No new usage since the last report"));
            steps.set(6, step("upload", "success", "Nothing to upload — scan check-in recorded"));
            when(logs.insert(any(), anyString())).thenReturn(true);

            service.record(report(steps, "not-needed", null, null));

            assertThat(stored().status()).isEqualTo("success");
        }

        @Test
        @DisplayName("a warning step makes a warning; a failed step makes a failure")
        void warningAndFailure() {
            assertThat(ClaudeUsageScanLogService.deriveStatus(
                    List.of(step("find-transcripts", "warning", "2 could not be read"), step("upload", "success", "")), null))
                    .isEqualTo("warning");
            assertThat(ClaudeUsageScanLogService.deriveStatus(
                    List.of(step("find-transcripts", "warning", ""), step("upload", "failed", "")), "upload"))
                    .isEqualTo("failed");
            assertThat(ClaudeUsageScanLogService.deriveStatus(List.of(), "upload"))
                    .as("an errorStep alone is a failure")
                    .isEqualTo("failed");
        }

        @Test
        @DisplayName("steps come back in catalogue order, and a step never sent is 'skipped'")
        void stepsNormalized() throws Exception {
            when(logs.insert(any(), anyString())).thenReturn(true);
            service.record(report(
                    List.of(step("upload", "failed", "upload failed"), step("start", "success", "Started")),
                    "failed", "upload", "upload failed for 2026-10-07: no response within 10s"));

            List<?> steps = json.readValue(storedSteps(), List.class);
            assertThat(steps).hasSize(ClaudeUsageScanLogService.STEPS.size());
            assertThat(steps.get(0).toString()).contains("key=start").contains("status=success");
            assertThat(steps.get(1).toString()).contains("key=find-transcripts").contains("status=skipped");
            assertThat(steps.get(6).toString()).contains("key=upload").contains("status=failed");
        }

        @Test
        @DisplayName("tokens and bearer values in an error are masked before they are stored")
        void redactsSecrets() {
            when(logs.insert(any(), anyString())).thenReturn(true);
            String secret = "coc_" + "x".repeat(40);
            service.record(report(
                    List.of(step("upload", "failed", "401 with Bearer " + secret)),
                    "failed", "upload", "rejected token " + secret));

            assertThat(stored().errorMessage()).doesNotContain(secret).contains("coc_***");
            assertThat(storedSteps()).doesNotContain(secret).contains("Bearer ***");
        }

        @Test
        @DisplayName("a resent record (same scanId) is ignored, reported as a duplicate")
        void resendIsIdempotent() {
            when(logs.insert(any(), anyString())).thenReturn(false);
            assertThat(service.record(report(allSuccessful(), "uploaded", null, null))).isEqualTo("duplicate");
        }

        @Test
        @DisplayName("records older than the retention window are pruned on insert")
        void prunes() {
            when(logs.insert(any(), anyString())).thenReturn(true);
            service.record(report(allSuccessful(), "uploaded", null, null));
            verify(logs).deleteStartedBefore(NOW.minus(ClaudeUsageScanLogService.RETENTION));
        }

        @Test
        @DisplayName("an email with no roster row is still recorded, without a member")
        void unknownEmailStillLogged() {
            when(members.findByEmail(anyString())).thenReturn(Optional.empty());
            when(logs.insert(any(), anyString())).thenReturn(true);
            service.record(report(allSuccessful(), "uploaded", null, null));
            assertThat(stored().memberId()).isNull();
        }

        @Test
        @DisplayName("bad input is a 400 and stores nothing")
        void rejectsBadInput() {
            var unknownStep = report(List.of(step("reboot", "success", "")), "uploaded", null, null);
            var badStatus = report(List.of(step("start", "great", "")), "uploaded", null, null);
            var badUpload = report(allSuccessful(), "sideways", null, null);
            var twice = report(List.of(step("start", "success", ""), step("start", "success", "")), "uploaded", null, null);
            var backwards = new ClaudeUsageScanLogService.Report(
                    UUID.randomUUID(), "a@example.com", "manual", null, NOW, NOW.minusSeconds(1),
                    null, null, null, null, null, null, List.of(), null, null);

            for (var bad : List.of(unknownStep, badStatus, badUpload, twice, backwards)) {
                assertThatThrownBy(() -> service.record(bad))
                        .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST));
            }
            verify(logs, never()).insert(any(), anyString());
        }
    }

    @Nested
    @DisplayName("list")
    class ListLogs {

        @Test
        @DisplayName("no range: the last 7 days, today included, in the team's reporting zone")
        void defaultRange() {
            when(logs.list(any(), any(), anyInt())).thenReturn(List.of());
            ClaudeUsageScanLogService.Page page = service.list(null, null);

            assertThat(page.to()).isEqualTo(LocalDate.of(2026, 10, 7));
            assertThat(page.from()).isEqualTo(LocalDate.of(2026, 10, 1));
            // Phnom Penh is UTC+7: 1 Oct 00:00 local is 30 Sep 17:00 UTC.
            verify(logs).list(eq(Instant.parse("2026-09-30T17:00:00Z")), eq(Instant.parse("2026-10-07T17:00:00Z")),
                    eq(ClaudeUsageScanLogService.MAX_LIST_ROWS + 1));
        }

        @Test
        @DisplayName("a reversed, one-sided or over-long range is a 400")
        void rejectsBadRanges() {
            LocalDate d = LocalDate.of(2026, 10, 7);
            assertThatThrownBy(() -> service.list(d, d.minusDays(1))).isInstanceOf(ApiException.class);
            assertThatThrownBy(() -> service.list(d, null)).isInstanceOf(ApiException.class);
            assertThatThrownBy(() -> service.list(d.minusDays(ClaudeUsageScanLogService.MAX_RANGE_DAYS), d))
                    .isInstanceOf(ApiException.class);
        }

        @Test
        @DisplayName("an unknown id is a 404")
        void detailNotFound() {
            UUID id = UUID.randomUUID();
            when(logs.findById(id)).thenReturn(Optional.empty());
            assertThatThrownBy(() -> service.detail(id))
                    .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.NOT_FOUND));
        }
    }

    /** A stored row, as the repository reads it back. */
    private static ClaudeUsageScanLog row(String email, String status, Instant finishedAt, Instant receivedAt, String error) {
        return new ClaudeUsageScanLog(
                UUID.randomUUID(), UUID.randomUUID(), null, email, null, null, "scheduled", "0.6.0",
                NOW.minusSeconds(3600), finishedAt, receivedAt, status, "not-attempted",
                null, null, null, null, null, error == null ? null : "upload", error, List.of());
    }

    @Nested
    @DisplayName("running scans (ADR-107)")
    class Running {

        private ClaudeUsageScanLogService.Start start() {
            return new ClaudeUsageScanLogService.Start(
                    UUID.randomUUID(), " Dana@Example.com ", "scheduled", "0.6.0", NOW.minusSeconds(2));
        }

        @Test
        @DisplayName("a start is stored as a running row with no finish time, no counts and no steps")
        void startStoredAsRunning() {
            when(logs.insertRunning(any())).thenReturn(true);

            assertThat(service.recordStart(start())).isEqualTo(ClaudeUsageScanLogService.STARTED);

            ArgumentCaptor<ClaudeUsageScanLog> log = ArgumentCaptor.forClass(ClaudeUsageScanLog.class);
            verify(logs).insertRunning(log.capture());
            assertThat(log.getValue().status()).isEqualTo(ClaudeUsageScanLogService.RUNNING);
            assertThat(log.getValue().email()).isEqualTo("dana@example.com");
            assertThat(log.getValue().finishedAt()).isNull();
            assertThat(log.getValue().durationMs()).isNull();
            assertThat(log.getValue().uploadStatus()).isEqualTo("not-attempted");
            assertThat(log.getValue().newTokens()).isNull();
            verify(logs, never()).insert(any(), anyString());
        }

        @Test
        @DisplayName("a start already stored (or arriving after its result) is a duplicate")
        void duplicateStart() {
            when(logs.insertRunning(any())).thenReturn(false);
            assertThat(service.recordStart(start())).isEqualTo(ClaudeUsageScanLogService.DUPLICATE);
        }

        @Test
        @DisplayName("a disabled member's start is refused like their result (ADR-105)")
        void disabledMemberStartRefused() {
            when(members.findByEmail("dana@example.com")).thenReturn(Optional.of(new ClaudeUsageMember(
                    UUID.randomUUID(), "dana@example.com", "Dana", "member", ClaudeUsageMember.STATUS_REMOVED, null, null, NOW, NOW)));

            assertThatThrownBy(() -> service.recordStart(start()))
                    .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.FORBIDDEN));
            verify(logs, never()).insertRunning(any());
        }

        @Test
        @DisplayName("a start without an id or time, or from the future, is a 400")
        void rejectsBadStart() {
            assertThatThrownBy(() -> service.recordStart(new ClaudeUsageScanLogService.Start(
                    null, "dana@example.com", "manual", null, NOW))).isInstanceOf(ApiException.class);
            assertThatThrownBy(() -> service.recordStart(new ClaudeUsageScanLogService.Start(
                    UUID.randomUUID(), "dana@example.com", "manual", null, NOW.plus(java.time.Duration.ofDays(2)))))
                    .isInstanceOf(ApiException.class);
            verify(logs, never()).insertRunning(any());
        }

        @Test
        @DisplayName("running stays running until the result timeout, then reads as incomplete; finished rows are untouched")
        void overdueRunningReadsAsIncomplete() {
            Instant fresh = NOW.minus(ClaudeUsageScanLogService.RESULT_TIMEOUT).plusSeconds(60);
            Instant stale = NOW.minus(ClaudeUsageScanLogService.RESULT_TIMEOUT).minusSeconds(60);
            when(logs.list(any(), any(), anyInt())).thenReturn(List.of(
                    row("a@example.com", "running", null, fresh, null),
                    row("b@example.com", "running", null, stale, null),
                    row("c@example.com", "success", NOW.minusSeconds(3590), stale, null)));

            List<String> statuses = service.list(null, null).logs().stream().map(ClaudeUsageScanLog::status).toList();

            assertThat(statuses).containsExactly(
                    ClaudeUsageScanLogService.RUNNING, ClaudeUsageScanLogService.INCOMPLETE, ClaudeUsageScanLogService.SUCCESS);
        }

        @Test
        @DisplayName("a result is still validated as before: never a running status from the CLI")
        void resultNeverRunning() {
            when(logs.insert(any(), anyString())).thenReturn(true);
            service.record(report(allSuccessful(), "uploaded", null, null));
            assertThat(stored().status()).isEqualTo(ClaudeUsageScanLogService.SUCCESS);
            assertThat(stored().finishedAt()).isNotNull();
        }
    }

    @Nested
    @DisplayName("a member's own scan logs (ADR-107)")
    class MemberView {

        @Test
        @DisplayName("lists only the caller's email, normalized, over the same range rules")
        void listsOwnEmail() {
            when(logs.listForEmail(eq("dana@example.com"), any(), any(), anyInt())).thenReturn(List.of());

            ClaudeUsageScanLogService.Page page = service.listFor(" Dana@Example.com ", null, null);

            assertThat(page.from()).isEqualTo(LocalDate.of(2026, 10, 1));
            verify(logs).listForEmail(eq("dana@example.com"), eq(Instant.parse("2026-09-30T17:00:00Z")),
                    eq(Instant.parse("2026-10-07T17:00:00Z")), eq(ClaudeUsageScanLogService.MAX_LIST_ROWS + 1));
            verify(logs, never()).list(any(), any(), anyInt());
        }

        @Test
        @DisplayName("error text is cut to its first line with file paths removed; the diagnosis words survive")
        void errorTextSanitized() {
            String raw = "upload failed (401): token is invalid at C:\\Users\\dana\\AppData\\npm\\upload.js\n"
                    + "    at postOnce (/usr/lib/node_modules/claude-team-usage/src/upload.js:90:11)";
            when(logs.listForEmail(any(), any(), any(), anyInt()))
                    .thenReturn(List.of(row("dana@example.com", "failed", NOW, NOW, raw)));

            String shown = service.listFor("dana@example.com", null, null).logs().get(0).errorMessage();

            assertThat(shown).isEqualTo("upload failed (401): token is invalid at [path]");
        }

        @Test
        @DisplayName("forMember: keeps a URL and network codes, replaces ~ and unix paths")
        void forMemberRules() {
            assertThat(ClaudeUsageScanLogService.forMember("cannot resolve the host for https://docs.example.com/api/ingest (ENOTFOUND)"))
                    .isEqualTo("cannot resolve the host for https://docs.example.com/api/ingest (ENOTFOUND)");
            assertThat(ClaudeUsageScanLogService.forMember("no transcripts in ~/.claude/projects"))
                    .isEqualTo("no transcripts in [path]");
            assertThat(ClaudeUsageScanLogService.forMember("EACCES: open /home/dana/.config/x.json"))
                    .isEqualTo("EACCES: open [path]");
            assertThat(ClaudeUsageScanLogService.forMember(null)).isNull();
        }

        @Test
        @DisplayName("an admin's copy keeps the full message")
        void adminSeesFullMessage() {
            String raw = "line one at C:\\x\\y.js\nline two";
            when(logs.list(any(), any(), anyInt())).thenReturn(List.of(row("dana@example.com", "failed", NOW, NOW, raw)));
            assertThat(service.list(null, null).logs().get(0).errorMessage()).isEqualTo(raw);
        }

        @Test
        @DisplayName("someone else's scan is a 404, exactly like a missing one")
        void othersScanIsNotFound() {
            ClaudeUsageScanLog theirs = row("someone@example.com", "success", NOW, NOW, null);
            when(logs.findById(theirs.id())).thenReturn(Optional.of(theirs));

            assertThatThrownBy(() -> service.detailFor("dana@example.com", theirs.id()))
                    .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.NOT_FOUND));
            verify(logs, never()).stepsJson(any());
        }

        @Test
        @DisplayName("their own scan comes back with sanitized steps")
        void ownScanDetail() {
            ClaudeUsageScanLog mine = row("dana@example.com", "failed", NOW, NOW, "boom");
            when(logs.findById(mine.id())).thenReturn(Optional.of(mine));
            when(logs.stepsJson(mine.id())).thenReturn(Optional.of(
                    "[{\"key\":\"find-transcripts\",\"status\":\"failed\",\"at\":null,\"detail\":\"missing C:\\\\Users\\\\dana\\\\.claude\"}]"));

            ClaudeUsageScanLog detail = service.detailFor("Dana@example.com", mine.id());

            assertThat(detail.steps()).singleElement().satisfies(s -> assertThat(s.detail()).isEqualTo("missing [path]"));
        }
    }

    @Test
    @DisplayName("the scan ID is the start time in Phnom Penh plus four characters of the client id")
    void scanRef() {
        ClaudeUsageScanLog log = new ClaudeUsageScanLog(
                UUID.randomUUID(), UUID.fromString("3f2a0000-0000-0000-0000-000000000000"), null, "a@example.com", null, null,
                "scheduled", "0.4.0", Instant.parse("2026-10-07T04:00:01Z"), Instant.parse("2026-10-07T04:00:09Z"), NOW,
                "success", "uploaded", 1, 1, 0, 0L, 1, null, null, List.of());
        assertThat(log.scanRef()).isEqualTo("SCAN-20261007-110001-3F2A");
    }
}
