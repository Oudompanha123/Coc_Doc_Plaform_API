package com.coc.docs.section;

import java.time.Instant;
import java.util.UUID;

/**
 * A doc set: {@code jex3.0}, {@code jex2.0}, {@code coach}, {@code idea},
 * {@code workflow}.
 *
 * <p>{@code dbBacked} is the migration coexistence switch. While it is false the
 * frontend still renders that section from {@code content/*.tsx}; flipping one
 * flag migrates one doc set, and flipping it back is the per-section rollback.
 * Sprint 1 flips it for {@code jex2.0} only.
 *
 * <p>// req: CONST-OPS-03, FR-NAV-005
 */
public record Section(UUID id, String slug, int order, boolean dbBacked, Instant createdAt) {}
