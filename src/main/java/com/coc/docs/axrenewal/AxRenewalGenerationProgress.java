package com.coc.docs.axrenewal;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What a {@code generating} run is doing right now (ADR-082): which step it is
 * on, and the model's own recent actions as it reports them.
 *
 * <p><b>In memory, on the backend running the generation</b> — the same choice
 * {@code AxRenewalLocalRunner} makes for its log (ADR-048). It lives exactly
 * as long as the run's process does, needs no migration, and nothing here is
 * worth keeping once the run has an outcome: the run's own row, its changed
 * files and its history (ADR-078) are the record. Another backend sharing the
 * database simply has no entry, and says so.
 *
 * <p><b>Nothing is estimated.</b> A step is reported when it actually begins;
 * an activity is a tool the model actually called or a note it actually
 * wrote. There is deliberately no percentage: nothing reports how much of its
 * work the model has left.
 *
 * <p>// req: ADR-082
 */
public final class AxRenewalGenerationProgress {

    /** Copying the attached source into a disposable staging folder. */
    public static final String STEP_PREPARING = "preparing";
    /** The local model is working on the staging copy. */
    public static final String STEP_WORKING = "working";
    /** Comparing the staging copy with the source to find what changed. */
    public static final String STEP_COMPARING = "comparing";

    /** The most activities kept per run — the newest win. */
    static final int MAX_ACTIVITIES = 200;

    /**
     * One thing the model did or said.
     *
     * @param kind {@code read}, {@code edit}, {@code write}, {@code search},
     *     {@code list}, {@code plan}, {@code note}, {@code tool}, {@code
     *     error} or {@code done}
     * @param path the file it was about, relative to the project, or null
     */
    public record Activity(Instant at, String kind, String text, String path) {}

    /** A run's progress as of now. */
    public record Snapshot(
            String step,
            Instant stepStartedAt,
            int filesRead,
            int filesChanged,
            int actions,
            List<Activity> activity,
            boolean earlierActivityDropped) {}

    /** Where the generator reports to — bound to one run by the runner. */
    public interface Listener {
        void step(String step);

        void activities(List<Activity> activities);

        Listener NONE = new Listener() {
            @Override
            public void step(String step) {}

            @Override
            public void activities(List<Activity> activities) {}
        };
    }

    private static final class Tracked {
        String step = STEP_PREPARING;
        Instant stepStartedAt;
        final Set<String> read = new HashSet<>();
        final Set<String> changed = new HashSet<>();
        int actions;
        final Deque<Activity> activity = new ArrayDeque<>();
        boolean dropped;
    }

    private final Map<UUID, Tracked> runs = new ConcurrentHashMap<>();
    private final Clock clock;

    public AxRenewalGenerationProgress() {
        this(Clock.systemUTC());
    }

    AxRenewalGenerationProgress(Clock clock) {
        this.clock = clock;
    }

    /** Starts tracking {@code runId}, at the preparing step. */
    public void begin(UUID runId) {
        Tracked t = new Tracked();
        t.stepStartedAt = clock.instant();
        runs.put(runId, t);
    }

    /** Stops tracking {@code runId} — its outcome is in the database now. */
    public void end(UUID runId) {
        runs.remove(runId);
    }

    /** A listener that records into {@code runId}'s entry, if it is still tracked. */
    public Listener listenerFor(UUID runId) {
        return new Listener() {
            @Override
            public void step(String step) {
                Tracked t = runs.get(runId);
                if (t == null) {
                    return;
                }
                synchronized (t) {
                    t.step = step;
                    t.stepStartedAt = clock.instant();
                }
            }

            @Override
            public void activities(List<Activity> activities) {
                Tracked t = runs.get(runId);
                if (t == null) {
                    return;
                }
                synchronized (t) {
                    for (Activity a : activities) {
                        record(t, a);
                    }
                }
            }
        };
    }

    private static void record(Tracked t, Activity a) {
        switch (a.kind()) {
            case "read" -> {
                t.actions++;
                if (a.path() != null) {
                    t.read.add(a.path());
                }
            }
            case "edit", "write" -> {
                t.actions++;
                if (a.path() != null) {
                    t.changed.add(a.path());
                }
            }
            case "search", "list", "plan", "tool" -> t.actions++;
            default -> {
                // note, error, done: what was said, not something done
            }
        }
        t.activity.addLast(a);
        while (t.activity.size() > MAX_ACTIVITIES) {
            t.activity.removeFirst();
            t.dropped = true;
        }
    }

    /** {@code runId}'s progress, or empty if this backend is not generating it. */
    public Optional<Snapshot> snapshot(UUID runId) {
        Tracked t = runs.get(runId);
        if (t == null) {
            return Optional.empty();
        }
        synchronized (t) {
            return Optional.of(new Snapshot(
                    t.step,
                    t.stepStartedAt,
                    t.read.size(),
                    t.changed.size(),
                    t.actions,
                    List.copyOf(t.activity),
                    t.dropped));
        }
    }
}
