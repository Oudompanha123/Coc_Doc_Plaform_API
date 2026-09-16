package com.coc.docs.tasks;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A row of {@code task} (V17, ADR-033). Deliberately thin — see the ADR for
 * what this is not (no assignee, no product/target reference, no priority).
 */
public record Task(
        UUID id,
        String title,
        LocalDate dueDate,
        String status,
        UUID createdBy,
        Instant createdAt,
        Instant completedAt) {

    public static final String STATUS_OPEN = "open";
    public static final String STATUS_DONE = "done";
}
