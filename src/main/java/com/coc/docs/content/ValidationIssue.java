package com.coc.docs.content;

/**
 * One reason a page body was rejected.
 *
 * @param path    dotted path to the offending node, e.g. {@code body[3].items[1].title}
 * @param message an editor-readable explanation, not a schema dump
 *
 * <p>Paths and messages are kept byte-identical to
 * {@code frontend/lib/content/validate.ts} so that the advisory client-side
 * check and the authoritative server-side check never disagree about
 * <em>why</em> something was refused. A test asserts a shared corpus of
 * fixtures produces the same issues on both sides.
 *
 * <p>// req: FR-CONT-009, ADR-012 §3
 */
public record ValidationIssue(String path, String message) {}
