package com.coc.docs.axrenewal;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The off switch, and the two ways it can be off.
 *
 * <p>These are the cases that matter in production: the deployed backend has
 * no {@code claude} binary and no configuration, and it must take the keyword
 * branch without touching the filesystem, starting a process, or failing. A
 * plain unit test — no Spring context, no Docker, no CLI — so it runs
 * everywhere, including on the machines that will never have Claude Code
 * installed.
 *
 * <p>Deliberately not tested here: a successful run. That needs a real Claude
 * Code installation, a real seat and a network, which is not something CI can
 * or should have. The parsing that a real run feeds is worth covering
 * separately if it ever grows past reading three fields.
 *
 * <p>// req: ADR-043, ADR-055
 */
class LocalClaudeAnalyzerTest {

    private final ObjectMapper json = new ObjectMapper();

    private static AxRenewalProject project() {
        return new AxRenewalProject(
                UUID.randomUUID(),
                "legacy-app",
                null,
                AxRenewalProject.STATUS_DRAFT,
                "package.json",
                "{\"name\":\"legacy-app\"}",
                Instant.now(),
                UUID.randomUUID(),
                null,
                UUID.randomUUID(),
                Instant.now(),
                Instant.now());
    }

    @Test
    @DisplayName("does nothing at all when no CLI path is configured — the deployed case")
    void unconfiguredIsSilentlyOff() {
        var analyzer = new LocalClaudeAnalyzer(new AxRenewalProperties(null, null), json);

        assertThat(analyzer.analyse(project(), null)).isEmpty();
    }

    @Test
    @DisplayName("treats a blank CLI path as unconfigured, not as a path to run")
    void blankCliPathIsUnconfigured() {
        var analyzer = new LocalClaudeAnalyzer(new AxRenewalProperties(null, "   "), json);

        assertThat(analyzer.analyse(project(), null)).isEmpty();
    }

    @Test
    @DisplayName("falls back rather than throwing when the configured binary is not there")
    void missingBinaryFallsBack(@TempDir Path tmp) {
        // A developer who mistypes the path gets the keyword analysis and a
        // log line naming what is wrong — never a failed request, because
        // analysis is a convenience on top of a plan somebody writes by hand.
        var analyzer =
                new LocalClaudeAnalyzer(new AxRenewalProperties(null, tmp.resolve("no-such-claude").toString()), json);

        assertThat(analyzer.analyse(project(), null)).isEmpty();
    }

    @Test
    @DisplayName("a configured API key alone does not turn the local path on")
    void apiKeyDoesNotEnableTheLocalRunner() {
        // The two settings are independent: anthropicApiKey is for a later
        // phase where the deployed site analyses for everyone, and must not
        // make this class start looking for a binary.
        var analyzer = new LocalClaudeAnalyzer(new AxRenewalProperties("sk-ant-whatever", null), json);

        assertThat(analyzer.analyse(project(), null)).isEmpty();
    }

    @Test
    @DisplayName("localClaudeConfigured is keyed only on the CLI path")
    void predicates() {
        assertThat(new AxRenewalProperties(null, null).localClaudeConfigured()).isFalse();
        assertThat(new AxRenewalProperties(null, "/usr/bin/claude").localClaudeConfigured()).isTrue();
    }

    @Test
    @DisplayName("a null or blank source path is treated as none given, not as a path to run (ADR-055)")
    void blankSourcePathIsUnconfigured() {
        // No CLI configured at all -- every spelling of "no source path"
        // must reach the exact same "off" answer regardless.
        var analyzer = new LocalClaudeAnalyzer(new AxRenewalProperties(null, null), json);

        assertThat(analyzer.analyse(project(), null)).isEmpty();
        assertThat(analyzer.analyse(project(), "   ")).isEmpty();
    }
}
