package com.coc.docs.nav;

import java.util.UUID;

/**
 * An admin's override of one static (ADMINISTRATION-block) item's label
 * and/or group — keyed by the item's href, the same identity {@code
 * isNavItemActive} already matches on in {@code nav-config.ts}. A field left
 * {@code null} means "use the code-defined default" for that field; there is
 * no row at all for an item nobody has ever overridden (same sparse-row
 * pattern as {@code notification_preference}, V33).
 */
public record NavItemOverride(String itemKey, String customLabel, UUID groupId, Integer sortOrder) {}
