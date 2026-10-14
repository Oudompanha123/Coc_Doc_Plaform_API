package com.coc.docs;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Guards against a text block whose last word needs the space before the
 * closing {@code """}: Java strips trailing whitespace on every text-block
 * line, so {@code "... returning """ + COLUMNS} compiles to
 * {@code "returningid, slug, ..."} and PostgreSQL refuses it at runtime. Six
 * queries in {@code LegalPageVersionRepository} and {@code NavGroupRepository}
 * shipped that way (2026-10-14), failing every legal-page save and publish.
 * Only the database integration tests could see it, and those need Docker —
 * this needs nothing. Write {@code returning\s"""} (or concatenate {@code " "})
 * instead.
 */
class TextBlockTrailingSpaceTest {

    /** A word, then spaces, then the closing quotes, then string concatenation. */
    private static final Pattern WORD_SPACE_CLOSE = Pattern.compile("\\w[ \\t]+\"\"\"\\s*\\+");

    @Test
    @DisplayName("no text block relies on a trailing space before its closing quotes")
    void no_text_block_relies_on_a_stripped_trailing_space() throws IOException {
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                for (int i = 0; i < lines.size(); i++) {
                    if (WORD_SPACE_CLOSE.matcher(lines.get(i)).find()) {
                        offenders.add(file + ":" + (i + 1) + "  " + lines.get(i).trim());
                    }
                }
            }
        }
        assertThat(offenders).as("text blocks that lose their trailing space").isEmpty();
    }
}
