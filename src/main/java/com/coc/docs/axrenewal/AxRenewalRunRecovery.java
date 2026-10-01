package com.coc.docs.axrenewal;

import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Closes implementation runs a stopped backend left "in progress" (ADR-073).
 *
 * <p>A Generate run's work happens on a background thread in one JVM. If that
 * JVM stops — a restart during development, a crash — the row stays at
 * {@code generating} for good: nothing is left to finish it, Cancel had no
 * process to kill, and its project could never be deleted because it still
 * had an "active" run.
 *
 * <p><b>Only runs that cannot be alive anywhere are touched.</b> The dev
 * database is shared by several developers' backends, so "this backend has no
 * process for it" proves nothing — a teammate's backend may still be
 * generating it. What is safe on a shared database:
 * <ul>
 *   <li>a committed {@code running} or {@code applying} row, which only exists
 *       if the transaction that sets and finishes it died half way; and
 *   <li>a {@code generating} row older than {@link
 *       AxRenewalImplementationRunner#GENERATING_ORPHANED_AFTER}, longer than
 *       any real Generate attempt can last.
 * </ul>
 * A younger stuck run is not left waiting for that: Cancel now closes a run
 * with no process (ADR-073), and every runner transition is compare-and-set,
 * so a late process cannot overwrite either outcome.
 *
 * <p>Best-effort: a failure here is logged, never allowed to stop the backend
 * from starting.
 *
 * <p>// req: ADR-073
 */
@Component
public class AxRenewalRunRecovery {

    private static final Logger log = LoggerFactory.getLogger(AxRenewalRunRecovery.class);

    static final String INTERRUPTED_APPLYING_REASON =
            "interrupted while applying — the backend stopped part way. Check the output destination before running"
                    + " it again; it may be partly updated.";

    private final AxRenewalImplementationRepository implementations;

    public AxRenewalRunRecovery(AxRenewalImplementationRepository implementations) {
        this.implementations = implementations;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverOnStartup() {
        try {
            int recovered = recover(Instant.now());
            if (recovered > 0) {
                log.warn("closed {} AX Renewal implementation run(s) left in progress by a stopped backend", recovered);
            }
        } catch (RuntimeException e) {
            log.error("could not recover interrupted AX Renewal implementation runs; they can still be cancelled", e);
        }
    }

    /**
     * @return how many runs this call actually closed — one already finished
     *     or closed by someone else in the meantime is skipped, not counted.
     */
    int recover(Instant now) {
        List<AxRenewalImplementationRun> orphaned =
                implementations.findOrphaned(now.minus(AxRenewalImplementationRunner.GENERATING_ORPHANED_AFTER));
        int recovered = 0;
        for (AxRenewalImplementationRun run : orphaned) {
            String reason = AxRenewalImplementationRun.STATUS_APPLYING.equals(run.status())
                    ? INTERRUPTED_APPLYING_REASON
                    : AxRenewalImplementationRunner.INTERRUPTED_REASON;
            // Compare-and-set on the status it was read with: a run that
            // moved on since the query is left exactly as it now is.
            if (implementations.endIfStatus(run.id(), run.status(), AxRenewalImplementationRun.STATUS_FAILED, reason)) {
                AxRenewalImplementationRunner.deleteStagingCopy(run.stagingPath());
                implementations.clearStaging(run.id());
                recovered++;
            }
        }
        return recovered;
    }
}
