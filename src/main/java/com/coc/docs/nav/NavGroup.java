package com.coc.docs.nav;

import java.util.UUID;

/**
 * A sidebar group an ADMINISTRATION-block item can belong to — either a
 * built-in one seeded by V77 (ADMINISTRATION, DEVELOPER) or a custom one an
 * admin created via "+ New group...". {@code builtin} groups can't be
 * renamed or deleted (checked in {@link NavService}, not here).
 */
public record NavGroup(UUID id, String name, boolean builtin, int sortOrder) {}
