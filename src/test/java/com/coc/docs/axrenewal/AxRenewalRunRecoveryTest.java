package com.coc.docs.axrenewal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

/**
 * Startup recovery of runs a stopped backend left "in progress" (ADR-073).
 *
 * <p>Which rows count as orphaned is {@code findOrphaned}'s SQL, covered
 * against real Postgres by {@code AxRenewalRunRecoveryIntegrationTest}. This
 * covers what recovery then does with each one — and what it must not do.
 *
 * <p>// req: ADR-073
 */
class AxRenewalRunRecoveryTest {

    private static final Instant NOW = Instant.parse("2026-10-01T09:00:00Z");

    private AxRenewalImplementationRepository implementations;
    private AxRenewalRunRecovery recovery;

    @BeforeEach
    void setUp() {
        implementations = mock(AxRenewalImplementationRepository.class);
        recovery = new AxRenewalRunRecovery(implementations);
    }

    private static AxRenewalImplementationRun run(String status, String stagingPath) {
        return new AxRenewalImplementationRun(
                UUID.randomUUID(),
                UUID.randomUUID(),
                status,
                null,
                null,
                Instant.EPOCH,
                UUID.randomUUID(),
                null,
                stagingPath,
                null,
                null);
    }

    @Test
    void asks_only_for_generating_runs_older_than_any_real_attempt_can_last() {
        recovery.recover(NOW);

        // Younger ones may still be alive on a teammate's backend sharing
        // this database — those are Cancel's to close, not recovery's.
        verify(implementations).findOrphaned(NOW.minus(AxRenewalImplementationRunner.GENERATING_ORPHANED_AFTER));
    }

    @Test
    void closes_a_stuck_generating_run_as_failed_and_removes_its_staging_copy() throws Exception {
        Path staging = Files.createTempDirectory(AxRenewalImplementationRunner.STAGING_PREFIX);
        AxRenewalImplementationRun stuck = run(AxRenewalImplementationRun.STATUS_GENERATING, staging.toString());
        when(implementations.findOrphaned(any())).thenReturn(List.of(stuck));
        when(implementations.endIfStatus(any(), any(), any(), any())).thenReturn(true);

        assertThat(recovery.recover(NOW)).isEqualTo(1);

        verify(implementations)
                .endIfStatus(
                        stuck.id(),
                        AxRenewalImplementationRun.STATUS_GENERATING,
                        AxRenewalImplementationRun.STATUS_FAILED,
                        AxRenewalImplementationRunner.INTERRUPTED_REASON);
        verify(implementations).clearStaging(stuck.id());
        assertThat(staging).doesNotExist();
    }

    @Test
    void says_so_plainly_when_a_run_was_interrupted_part_way_through_applying() {
        AxRenewalImplementationRun applying = run(AxRenewalImplementationRun.STATUS_APPLYING, null);
        when(implementations.findOrphaned(any())).thenReturn(List.of(applying));
        when(implementations.endIfStatus(any(), any(), any(), any())).thenReturn(true);

        recovery.recover(NOW);

        ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
        verify(implementations)
                .endIfStatus(
                        eq(applying.id()),
                        eq(AxRenewalImplementationRun.STATUS_APPLYING),
                        eq(AxRenewalImplementationRun.STATUS_FAILED),
                        reason.capture());
        assertThat(reason.getValue()).contains("partly updated");
    }

    @Test
    void leaves_a_run_alone_that_moved_on_since_it_was_read() throws Exception {
        // Compare-and-set lost: it finished, or someone cancelled it, between
        // the query and the update. Its staging copy is not ours to remove.
        Path staging = Files.createTempDirectory(AxRenewalImplementationRunner.STAGING_PREFIX);
        AxRenewalImplementationRun moved = run(AxRenewalImplementationRun.STATUS_GENERATING, staging.toString());
        when(implementations.findOrphaned(any())).thenReturn(List.of(moved));
        when(implementations.endIfStatus(any(), any(), any(), any())).thenReturn(false);

        try {
            assertThat(recovery.recover(NOW)).isZero();

            verify(implementations, never()).clearStaging(any());
            assertThat(staging).isDirectory();
        } finally {
            AxRenewalImplementationRunner.deleteQuietly(staging);
        }
    }

    @Test
    void never_deletes_a_path_that_is_not_one_of_its_own_staging_copies(@TempDir Path elsewhere) throws Exception {
        Path notStaging = Files.createDirectory(elsewhere.resolve("renewed-app"));
        Files.writeString(notStaging.resolve("keep.txt"), "the team's own code");
        when(implementations.findOrphaned(any()))
                .thenReturn(List.of(run(AxRenewalImplementationRun.STATUS_GENERATING, notStaging.toString())));
        when(implementations.endIfStatus(any(), any(), any(), any())).thenReturn(true);

        recovery.recover(NOW);

        assertThat(notStaging.resolve("keep.txt")).exists();
    }

    @Test
    void does_nothing_when_nothing_is_stuck() {
        when(implementations.findOrphaned(any())).thenReturn(List.of());

        assertThat(recovery.recover(NOW)).isZero();

        verify(implementations, never()).endIfStatus(any(), any(), any(), any());
    }

    @Test
    void a_failure_is_logged_and_never_stops_the_backend_from_starting() {
        when(implementations.findOrphaned(any())).thenThrow(new IllegalStateException("database unavailable"));

        assertThatCode(recovery::recoverOnStartup).doesNotThrowAnyException();
    }
}
