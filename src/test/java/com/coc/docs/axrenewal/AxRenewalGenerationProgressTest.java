package com.coc.docs.axrenewal;

import static org.assertj.core.api.Assertions.assertThat;

import com.coc.docs.axrenewal.AxRenewalGenerationProgress.Activity;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * The in-memory record of what a generating run is doing (ADR-082).
 *
 * <p>// req: ADR-082
 */
class AxRenewalGenerationProgressTest {

    private static final Instant T0 = Instant.parse("2026-10-01T09:00:00Z");
    private final AxRenewalGenerationProgress progress =
            new AxRenewalGenerationProgress(Clock.fixed(T0, ZoneOffset.UTC));
    private final UUID runId = UUID.randomUUID();

    private static Activity act(String kind, String path) {
        return new Activity(T0, kind, kind + " " + path, path);
    }

    @Test
    void a_run_starts_at_preparing() {
        progress.begin(runId);

        assertThat(progress.snapshot(runId)).hasValueSatisfying(s -> {
            assertThat(s.step()).isEqualTo(AxRenewalGenerationProgress.STEP_PREPARING);
            assertThat(s.stepStartedAt()).isEqualTo(T0);
            assertThat(s.activity()).isEmpty();
        });
    }

    @Test
    void a_run_nobody_is_generating_has_no_progress() {
        assertThat(progress.snapshot(runId)).isEmpty();
    }

    @Test
    void steps_and_activity_are_recorded_as_they_are_reported() {
        progress.begin(runId);
        var listener = progress.listenerFor(runId);

        listener.step(AxRenewalGenerationProgress.STEP_WORKING);
        listener.activities(List.of(act("read", "a.ts"), act("note", null)));

        assertThat(progress.snapshot(runId)).hasValueSatisfying(s -> {
            assertThat(s.step()).isEqualTo(AxRenewalGenerationProgress.STEP_WORKING);
            assertThat(s.activity()).extracting(Activity::kind).containsExactly("read", "note");
        });
    }

    @Test
    void counts_distinct_files_read_and_changed_and_every_action() {
        progress.begin(runId);
        progress.listenerFor(runId).activities(List.of(
                act("read", "a.ts"),
                act("read", "a.ts"),
                act("read", "b.ts"),
                act("edit", "a.ts"),
                act("write", "c.ts"),
                act("edit", "a.ts"),
                act("search", null),
                // What was said, not done: no action.
                act("note", null),
                act("done", null)));

        assertThat(progress.snapshot(runId)).hasValueSatisfying(s -> {
            assertThat(s.filesRead()).isEqualTo(2);
            assertThat(s.filesChanged()).isEqualTo(2);
            assertThat(s.actions()).isEqualTo(7);
        });
    }

    @Test
    void keeps_only_the_newest_activities_and_says_earlier_ones_were_dropped() {
        progress.begin(runId);
        progress.listenerFor(runId).activities(IntStream.range(0, AxRenewalGenerationProgress.MAX_ACTIVITIES + 5)
                .mapToObj(i -> act("read", "f" + i + ".ts"))
                .toList());

        assertThat(progress.snapshot(runId)).hasValueSatisfying(s -> {
            assertThat(s.activity()).hasSize(AxRenewalGenerationProgress.MAX_ACTIVITIES);
            assertThat(s.activity().get(0).path()).isEqualTo("f5.ts");
            assertThat(s.earlierActivityDropped()).isTrue();
            // Counts are of everything, not only what is still listed.
            assertThat(s.filesRead()).isEqualTo(AxRenewalGenerationProgress.MAX_ACTIVITIES + 5);
        });
    }

    @Test
    void ending_a_run_forgets_it_and_late_reports_are_ignored() {
        progress.begin(runId);
        var listener = progress.listenerFor(runId);

        progress.end(runId);
        listener.step(AxRenewalGenerationProgress.STEP_COMPARING);
        listener.activities(List.of(act("read", "a.ts")));

        assertThat(progress.snapshot(runId)).isEmpty();
    }
}
