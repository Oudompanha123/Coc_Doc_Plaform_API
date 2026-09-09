package com.coc.docs.content;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * The closed block vocabulary — ten types, reduced from the 32 CSS-class
 * structures the legacy {@code .tsx} content uses (CONST-TECH-04).
 *
 * <p>Mirrors {@code BLOCK_TYPES} in {@code frontend/lib/content/blocks.ts}.
 * There is deliberately no {@code raw-html} type: without a field where markup
 * is legal, stored XSS is prevented structurally rather than by filtering
 * (ADR-010 §3).
 *
 * <p>// req: FR-CONT-009, CONST-TECH-04, NFR-SEC-SANITIZE
 */
public enum BlockType {
    HEADING("heading"),
    PARAGRAPH("paragraph"),
    LIST("list"),
    TABLE("table"),
    CODE("code"),
    CALLOUT("callout"),
    STEPS("steps"),
    CARDS("cards"),
    IMAGE("image"),
    EMBED("embed");

    private final String wire;

    BlockType(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    /** Block types that may contain other blocks. Depth is bounded — see {@link BlockBodyValidator}. */
    public static final List<BlockType> NESTING = List.of(CALLOUT, STEPS);

    /** Returns null for an unknown type; the validator turns that into an issue naming the vocabulary. */
    public static BlockType fromWire(String value) {
        if (value == null) {
            return null;
        }
        for (BlockType t : values()) {
            if (t.wire.equals(value)) {
                return t;
            }
        }
        return null;
    }

    /** The vocabulary as an editor-readable list, used in validation messages. */
    public static String vocabulary() {
        return Arrays.stream(values()).map(BlockType::wire).collect(Collectors.joining(", "));
    }
}
