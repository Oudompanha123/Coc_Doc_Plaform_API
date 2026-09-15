package com.coc.docs.nav;

import java.util.List;

/** A top-level sidebar/docs product, with its ordered sub-items attached. */
public record NavProduct(String key, String label, String icon, int sortOrder, List<NavItem> items) {}
