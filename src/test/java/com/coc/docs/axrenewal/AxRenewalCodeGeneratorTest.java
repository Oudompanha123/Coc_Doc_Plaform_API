package com.coc.docs.axrenewal;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The parts of {@link AxRenewalCodeGenerator} that don't need a real Claude
 * Code installation to verify: the on/off switch, the fallback when
 * misconfigured, and — the real substance — the staging copy and the diff
 * that decides what a Generate run actually changed. A real generation run
 * needs a real CLI and is not covered here, same reasoning as {@link
 * LocalClaudeAnalyzerTest}.
 *
 * <p>// req: ADR-044
 */
class AxRenewalCodeGeneratorTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    @DisplayName("cannot generate with no CLI configured")
    void cannotGenerateUnconfigured() {
        var generator = new AxRenewalCodeGenerator(new AxRenewalProperties(null, null), json);
        assertThat(generator.canGenerate()).isFalse();
    }

    @Test
    @DisplayName("can generate once a CLI path is configured — the source folder is a per-call argument now, not configuration (ADR-052)")
    void canGenerateOnceTheCliIsConfigured() {
        var generator = new AxRenewalCodeGenerator(new AxRenewalProperties(null, "/usr/bin/claude"), json);
        assertThat(generator.canGenerate()).isTrue();
    }

    @Test
    @DisplayName("falls back rather than throwing when the configured binary is not there")
    void missingBinaryFallsBack(@TempDir Path tmp) throws IOException {
        Path source = tmp.resolve("source");
        Files.createDirectories(source);
        var generator =
                new AxRenewalCodeGenerator(new AxRenewalProperties(null, tmp.resolve("no-such-claude").toString()), json);
        assertThat(generator.generate("some plan", tmp.resolve("staging"), process -> {}, source.toString()))
                .isEmpty();
    }

    @Test
    @DisplayName("falls back rather than throwing when the given source path is not a real directory")
    void missingSourcePathFallsBack(@TempDir Path tmp) {
        // The CLI path itself does not need to be real here -- canGenerate()
        // only checks it is configured, and the source-path check in
        // generate() is reached (and fails) before the binary is ever
        // touched.
        var generator = new AxRenewalCodeGenerator(new AxRenewalProperties(null, "/usr/bin/claude"), json);
        assertThat(generator.generate(
                        "some plan", tmp.resolve("staging"), process -> {}, tmp.resolve("no-such-source").toString()))
                .isEmpty();
    }

    @Test
    @DisplayName("copyTree copies real files and skips dependency/build directories")
    void copyTreeSkipsDependencyDirectories(@TempDir Path tmp) throws IOException {
        Path source = tmp.resolve("source");
        Files.createDirectories(source.resolve("src"));
        Files.writeString(source.resolve("src/app.js"), "console.log(1)");
        Files.createDirectories(source.resolve("node_modules/pkg"));
        Files.writeString(source.resolve("node_modules/pkg/index.js"), "module.exports = {}");

        Path target = tmp.resolve("staged");
        AxRenewalCodeGenerator.copyTree(source, target);

        assertThat(Files.readString(target.resolve("src/app.js"))).isEqualTo("console.log(1)");
        assertThat(Files.exists(target.resolve("node_modules"))).isFalse();
    }

    @Test
    @DisplayName("diffAgainstSource finds a real modification, creation, and deletion, and ignores an unchanged file")
    void diffFindsRealChanges(@TempDir Path tmp) throws IOException {
        Path source = tmp.resolve("source");
        Files.createDirectories(source);
        Files.writeString(source.resolve("unchanged.js"), "same");
        Files.writeString(source.resolve("modified.js"), "old");
        Files.writeString(source.resolve("deleted.js"), "gone soon");

        Path staged = tmp.resolve("staged");
        AxRenewalCodeGenerator.copyTree(source, staged);
        Files.writeString(staged.resolve("modified.js"), "new");
        Files.delete(staged.resolve("deleted.js"));
        Files.writeString(staged.resolve("created.js"), "brand new");

        List<AxRenewalCodeGenerator.GeneratedFile> changes = AxRenewalCodeGenerator.diffAgainstSource(source, staged);

        assertThat(changes).hasSize(3);
        assertThat(changes)
                .filteredOn(c -> c.path().equals("modified.js"))
                .singleElement()
                .satisfies(c -> {
                    assertThat(c.changeKind()).isEqualTo(AxRenewalGeneratedChange.KIND_MODIFY);
                    assertThat(c.beforeContent()).isEqualTo("old");
                    assertThat(c.afterContent()).isEqualTo("new");
                });
        assertThat(changes)
                .filteredOn(c -> c.path().equals("deleted.js"))
                .singleElement()
                .satisfies(c -> {
                    assertThat(c.changeKind()).isEqualTo(AxRenewalGeneratedChange.KIND_DELETE);
                    assertThat(c.beforeContent()).isEqualTo("gone soon");
                    assertThat(c.afterContent()).isNull();
                });
        assertThat(changes)
                .filteredOn(c -> c.path().equals("created.js"))
                .singleElement()
                .satisfies(c -> {
                    assertThat(c.changeKind()).isEqualTo(AxRenewalGeneratedChange.KIND_CREATE);
                    assertThat(c.beforeContent()).isNull();
                    assertThat(c.afterContent()).isEqualTo("brand new");
                });
    }

    @Test
    @DisplayName("skips a binary file that cannot be decoded as UTF-8 rather than guessing at it")
    void skipsBinaryFiles(@TempDir Path tmp) throws IOException {
        Path source = tmp.resolve("source");
        Files.createDirectories(source);
        Files.write(source.resolve("image.png"), new byte[] {(byte) 0x89, 'P', 'N', 'G', (byte) 0xFF, (byte) 0xFE, 0, 1, 2});

        Path staged = tmp.resolve("staged");
        AxRenewalCodeGenerator.copyTree(source, staged);
        Files.write(staged.resolve("image.png"), new byte[] {(byte) 0x89, 'P', 'N', 'G', 9, 9, 9});

        List<AxRenewalCodeGenerator.GeneratedFile> changes = AxRenewalCodeGenerator.diffAgainstSource(source, staged);
        assertThat(changes).isEmpty();
    }
}
