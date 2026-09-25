package com.coc.docs.axrenewal;

import java.util.UUID;

/**
 * A row of {@code ax_renewal_generated_change} — one file a Generate run
 * actually found different between the untouched attached source and its
 * own disposable staged copy (ADR-044).
 *
 * @param beforeContent the file's real content before generation — null
 *     only for {@link #KIND_CREATE}, since there was no "before".
 * @param afterContent the file's real content after generation — null only
 *     for {@link #KIND_DELETE}, since there is no "after".
 */
public record AxRenewalGeneratedChange(
        UUID id, UUID runId, String path, String changeKind, String beforeContent, String afterContent, int position) {

    public static final String KIND_CREATE = "create";
    public static final String KIND_MODIFY = "modify";
    public static final String KIND_DELETE = "delete";
}
