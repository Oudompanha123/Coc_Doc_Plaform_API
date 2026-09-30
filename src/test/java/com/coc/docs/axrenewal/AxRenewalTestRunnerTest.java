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
 * {@link AxRenewalTestRunner} — the honest refusals ({@code run(String)}'s
 * own preconditions) and the real process outcome ({@link
 * AxRenewalTestRunner#runCommand}, exercised with a small portable command
 * instead of a real {@code npm install}, the same substitution {@code
 * AxRenewalImplementationRunnerTest}/{@code AxRenewalLocalRunnerTest} already
 * make for their own subprocesses).
 *
 * <p>// req: ADR-062
 */
class AxRenewalTestRunnerTest {

    private final AxRenewalTestRunner runner = new AxRenewalTestRunner(new ObjectMapper());

    private static boolean windows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    @Test
    @DisplayName("reports honestly rather than executing anything when there is no staging path at all")
    void noStagingPathIsNotExecuted() {
        AxRenewalTestRunner.Result result = runner.run(null);
        assertThat(result.executed()).isFalse();
        assertThat(result.note()).contains("no staged changes");
    }

    @Test
    @DisplayName("reports honestly rather than executing anything when the staging directory no longer exists")
    void missingStagingDirectoryIsNotExecuted(@TempDir Path tmp) {
        AxRenewalTestRunner.Result result = runner.run(tmp.resolve("long-gone").toString());
        assertThat(result.executed()).isFalse();
        assertThat(result.note()).contains("no longer on disk");
    }

    @Test
    @DisplayName("reports honestly rather than executing anything when package.json has no test script")
    void noTestScriptIsNotExecuted(@TempDir Path staging) throws IOException {
        Files.writeString(staging.resolve("package.json"), "{\"scripts\": {\"dev\": \"next dev\"}}");
        AxRenewalTestRunner.Result result = runner.run(staging.toString());
        assertThat(result.executed()).isFalse();
        assertThat(result.note()).contains("no \"test\" script");
    }

    @Test
    @DisplayName("reports honestly rather than executing anything when there is no package.json at all")
    void noPackageJsonIsNotExecuted(@TempDir Path staging) {
        AxRenewalTestRunner.Result result = runner.run(staging.toString());
        assertThat(result.executed()).isFalse();
        assertThat(result.note()).contains("no \"test\" script");
    }

    @Test
    @DisplayName("runCommand reports the real exit code and real output of a command that succeeds")
    void runCommandReportsARealSuccess(@TempDir Path cwd) {
        List<String> command = windows()
                ? List.of("cmd.exe", "/c", "echo all good")
                : List.of("sh", "-c", "echo all good");

        AxRenewalTestRunner.Result result = runner.runCommand(command, cwd);

        assertThat(result.executed()).isTrue();
        assertThat(result.exitCode()).isZero();
        assertThat(result.output()).anySatisfy(line -> assertThat(line).contains("all good"));
        assertThat(result.note()).isNull();
    }

    @Test
    @DisplayName("runCommand reports the real nonzero exit code of a command that fails -- that is a legitimate result, not a refusal")
    void runCommandReportsARealFailureHonestly(@TempDir Path cwd) {
        List<String> command =
                windows() ? List.of("cmd.exe", "/c", "exit 7") : List.of("sh", "-c", "exit 7");

        AxRenewalTestRunner.Result result = runner.runCommand(command, cwd);

        assertThat(result.executed()).isTrue();
        assertThat(result.exitCode()).isEqualTo(7);
    }

    @Test
    @DisplayName("runCommand degrades honestly rather than throwing when the command cannot even be started")
    void runCommandDegradesWhenTheProcessCannotStart(@TempDir Path cwd) {
        AxRenewalTestRunner.Result result = runner.runCommand(List.of("no-such-executable-anywhere"), cwd);

        assertThat(result.executed()).isFalse();
        assertThat(result.note()).contains("could not start");
    }
}
