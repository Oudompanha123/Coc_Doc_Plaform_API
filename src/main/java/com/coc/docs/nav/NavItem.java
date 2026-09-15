package com.coc.docs.nav;

/**
 * One sub-item under a product in the sidebar/docs nav. {@code (productKey,
 * slug)} is the natural key everywhere else in the app already keys content
 * by (see {@code docs_content}), so it is what identifies a row here too —
 * the surrogate {@code nav_item.id} column never needs to leave the
 * repository.
 */
public record NavItem(String productKey, String slug, String label, int sortOrder) {}
