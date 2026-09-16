package com.coc.docs.nav;

import java.util.List;

/**
 * A top-level sidebar/docs product, with its ordered sub-items attached.
 *
 * <p>{@code minRole} is the lowest {@code app_role} that may view this
 * product (V18, extends ADR-033) — {@code "user"} unless a row was
 * explicitly restricted, so creating a product never silently hides it.
 */
public record NavProduct(String key, String label, String icon, int sortOrder, String minRole, List<NavItem> items) {}
