package com.coc.docs.claudeusage;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link ClaudeUsageTrackingPauseRepository} kept in memory, with the same
 * rules as the SQL: one open pause per member, opening twice is a no-op, and
 * close stamps only the open one. Lets a test walk disable -> retry -> enable
 * -> retry -> new report through the real services without a database. The
 * SQL itself is covered by {@code ClaudeUsageTrackingPauseRepositoryIntegrationTest}.
 */
public class InMemoryTrackingPauses extends ClaudeUsageTrackingPauseRepository {

    private final Map<UUID, List<Pause>> byMember = new ConcurrentHashMap<>();

    public InMemoryTrackingPauses() {
        super(null);
    }

    @Override
    public void open(UUID memberId, Instant at) {
        List<Pause> list = byMember.computeIfAbsent(memberId, id -> new ArrayList<>());
        if (list.stream().noneMatch(p -> p.resumedAt() == null)) {
            list.add(new Pause(at, null));
        }
    }

    @Override
    public void close(UUID memberId, Instant at) {
        List<Pause> list = byMember.getOrDefault(memberId, new ArrayList<>());
        list.replaceAll(p -> p.resumedAt() == null
                ? new Pause(p.disabledAt(), at.isBefore(p.disabledAt()) ? p.disabledAt() : at)
                : p);
    }

    @Override
    public List<Pause> listFor(UUID memberId) {
        return List.copyOf(byMember.getOrDefault(memberId, List.of()));
    }

    /** Seeds a finished window directly, for tests that start after an Enable. */
    public void add(UUID memberId, Instant disabledAt, Instant resumedAt) {
        byMember.computeIfAbsent(memberId, id -> new ArrayList<>()).add(new Pause(disabledAt, resumedAt));
    }
}
