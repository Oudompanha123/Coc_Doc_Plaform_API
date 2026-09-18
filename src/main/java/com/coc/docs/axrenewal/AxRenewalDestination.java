package com.coc.docs.axrenewal;

import java.time.Instant;
import java.util.UUID;

/** A row of {@code ax_renewal_destination} — where generated code would be written (ADR-037). */
public record AxRenewalDestination(
        UUID projectId, String kind, String target, String branch, String notes, Instant updatedAt, UUID updatedBy) {

    public static final String KIND_GIT_REPO = "git_repo";
    public static final String KIND_FILESYSTEM_PATH = "filesystem_path";
    public static final String KIND_OTHER = "other";
}
