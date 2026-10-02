package com.coc.docs.axrenewal;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
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
    @DisplayName("can generate once a CLI path is configured — the source folder is a per-call argument now, not configuration (ADR-055)")
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
        assertThat(generator.generate(
                        AxRenewalImplementationRun.RUN_KIND_PLAN,
                        "some plan",
                        tmp.resolve("staging"),
                        process -> {},
                        source.toString(),
                        AxRenewalGenerationProgress.Listener.NONE))
                .isEmpty();
    }

    /**
     * A stand-in CLI: drains the prompt from stdin, prints a few stream-json
     * events (and one line that isn't JSON), and changes one file in its
     * working directory, the staging copy — the shape of a real run (ADR-082).
     */
    private static Path fakeStreamingCli(Path dir) throws IOException {
        String[] events = {
            "{\"type\":\"system\",\"subtype\":\"init\"}",
            "{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"tool_use\",\"name\":\"Edit\",\"input\":{\"file_path\":\"src/App.tsx\"}}]}}",
            "not json at all",
            "{\"type\":\"result\",\"subtype\":\"success\",\"num_turns\":3,\"result\":\"done\"}",
        };
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        StringBuilder script = new StringBuilder();
        if (windows) {
            script.append("@echo off\r\nmore > nul\r\n");
            for (int i = 0; i < 3; i++) {
                script.append("echo ").append(events[i]).append("\r\n");
            }
            script.append("echo export const App = 2;> src\\App.tsx\r\n");
            script.append("echo ").append(events[3]).append("\r\n");
        } else {
            script.append("#!/bin/sh\ncat > /dev/null\n");
            for (int i = 0; i < 3; i++) {
                script.append("echo '").append(events[i]).append("'\n");
            }
            script.append("echo 'export const App = 2;' > src/App.tsx\n");
            script.append("echo '").append(events[3]).append("'\n");
        }
        Path cli = dir.resolve(windows ? "fake-claude.cmd" : "fake-claude.sh");
        Files.writeString(cli, script.toString());
        if (!windows) {
            Files.setPosixFilePermissions(cli, Set.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE));
        }
        return cli;
    }

    @Test
    @DisplayName("reads the CLI's stream as it arrives: each step, and what the model reports doing (ADR-082)")
    void reportsStepsAndActivityFromTheStream(@TempDir Path tmp) throws IOException {
        Path source = tmp.resolve("source");
        Files.createDirectories(source.resolve("src"));
        Files.writeString(source.resolve("src/App.tsx"), "export const App = 1;\n");
        var generator = new AxRenewalCodeGenerator(
                new AxRenewalProperties(null, fakeStreamingCli(tmp).toString()), json);
        List<String> steps = new ArrayList<>();
        List<AxRenewalGenerationProgress.Activity> activity = new ArrayList<>();

        var result = generator.generate(
                AxRenewalImplementationRun.RUN_KIND_PLAN,
                "some plan",
                tmp.resolve("staging"),
                process -> {},
                source.toString(),
                new AxRenewalGenerationProgress.Listener() {
                    @Override
                    public void step(String step) {
                        steps.add(step);
                    }

                    @Override
                    public void activities(List<AxRenewalGenerationProgress.Activity> activities) {
                        activity.addAll(activities);
                    }
                });

        assertThat(steps).containsExactly(
                AxRenewalGenerationProgress.STEP_PREPARING,
                AxRenewalGenerationProgress.STEP_WORKING,
                AxRenewalGenerationProgress.STEP_COMPARING);
        // The line that isn't JSON is skipped, not fatal.
        assertThat(activity).extracting(AxRenewalGenerationProgress.Activity::text).containsExactly(
                "Connected to the local model — starting work",
                "Editing src/App.tsx",
                "The model finished its work in 3 turns");
        // And the generation's real answer is still the files.
        assertThat(result).hasValueSatisfying(r -> assertThat(r.files())
                .extracting(AxRenewalCodeGenerator.GeneratedFile::path)
                .containsExactly("src/App.tsx"));
    }

    @Test
    @DisplayName("a progress listener that throws costs that update, never the generation (ADR-082)")
    void aFailingListenerDoesNotFailTheGeneration(@TempDir Path tmp) throws IOException {
        Path source = tmp.resolve("source");
        Files.createDirectories(source.resolve("src"));
        Files.writeString(source.resolve("src/App.tsx"), "export const App = 1;\n");
        var generator = new AxRenewalCodeGenerator(
                new AxRenewalProperties(null, fakeStreamingCli(tmp).toString()), json);

        var result = generator.generate(
                AxRenewalImplementationRun.RUN_KIND_PLAN,
                "some plan",
                tmp.resolve("staging"),
                process -> {},
                source.toString(),
                new AxRenewalGenerationProgress.Listener() {
                    @Override
                    public void step(String step) {
                        throw new IllegalStateException("view broke");
                    }

                    @Override
                    public void activities(List<AxRenewalGenerationProgress.Activity> activities) {
                        throw new IllegalStateException("view broke");
                    }
                });

        assertThat(result).isPresent();
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
                        AxRenewalImplementationRun.RUN_KIND_PLAN,
                        "some plan",
                        tmp.resolve("staging"),
                        process -> {},
                        tmp.resolve("no-such-source").toString(),
                        AxRenewalGenerationProgress.Listener.NONE))
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
