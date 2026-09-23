package com.coc.docs.axrenewal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.coc.docs.web.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * What can be tested without a Claude Code binary: the prompt's shape, and
 * the refusal on a machine that has no CLI configured.
 *
 * <p>The subprocess itself is not mocked. Spawning a real process to assert
 * on a fake one's arguments would test {@link ProcessBuilder}, not this —
 * what matters here is that nothing reaches a shell (visible in the source:
 * an argument array, prompt over stdin) and that the text handed to the
 * model is bounded and ordered.
 *
 * <p>// req: ADR-044
 */
class LocalClaudeChatTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static AxRenewalProject project(String manifestFilename, String manifestContent) {
        return new AxRenewalProject(
                UUID.randomUUID(),
                "Legacy Portal",
                "The 2011 intranet",
                AxRenewalProject.STATUS_ANALYZED,
                manifestFilename,
                manifestContent,
                Instant.EPOCH,
                null,
                null,
                null,
                Instant.EPOCH,
                Instant.EPOCH);
    }

    @Nested
    class WhenNothingIsConfigured {

        @Test
        void refuses_rather_than_pretending_to_answer() {
            LocalClaudeChat chat = new LocalClaudeChat(new AxRenewalProperties(null, null, null), JSON);

            assertThatThrownBy(() -> chat.reply(project("composer.json", "{}"), List.of(), "hello"))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("no local model is configured");
        }

        @Test
        void a_configured_path_that_is_not_a_file_says_so() {
            LocalClaudeChat chat = new LocalClaudeChat(
                    new AxRenewalProperties(null, "C:\\nope\\claude.exe", null), JSON);

            assertThatThrownBy(() -> chat.reply(project(null, null), List.of(), "hello"))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("does not point at a file");
        }
    }

    @Nested
    class ThePrompt {

        @Test
        void puts_the_new_message_last_so_it_is_what_gets_answered() {
            String prompt = LocalClaudeChat.prompt(
                    project("package.json", "{\"name\":\"portal\"}"),
                    List.of(new LocalClaudeChat.Turn("you", "what runs this?"),
                            new LocalClaudeChat.Turn("assistant", "Node 8.")),
                    "how old is that?",
                    false);

            assertThat(prompt).contains("Their new message:");
            assertThat(prompt.indexOf("how old is that?")).isGreaterThan(prompt.indexOf("Node 8."));
        }

        @Test
        void says_the_source_was_not_seen_when_it_was_not() {
            String manifestOnly = LocalClaudeChat.prompt(project("pom.xml", "<project/>"), List.of(), "hi", false);
            String withSource = LocalClaudeChat.prompt(project("pom.xml", "<project/>"), List.of(), "hi", true);

            // The distinction the whole feature's honesty rests on: a model
            // that read one dependency file must not be described as one
            // that read the code.
            assertThat(manifestOnly).contains("You have not been given the source");
            assertThat(withSource).contains("source is available to you");
        }

        @Test
        void keeps_the_newest_turns_when_a_conversation_runs_long() {
            List<LocalClaudeChat.Turn> history = IntStream.range(0, 50)
                    .mapToObj(i -> new LocalClaudeChat.Turn(i % 2 == 0 ? "you" : "assistant", "turn " + i))
                    .toList();

            String prompt = LocalClaudeChat.prompt(project(null, null), history, "and now?", false);

            assertThat(prompt).contains("turn 49").contains("turn 30");
            // Dropped from the front, so the model sees what was just said
            // rather than how the conversation opened.
            assertThat(prompt).doesNotContain("turn 29").doesNotContain("turn 0\n");
        }

        @Test
        void truncates_a_message_far_past_any_real_question() {
            String enormous = "x".repeat(LocalClaudeChat.MAX_TURN_CHARS * 3);

            String prompt = LocalClaudeChat.prompt(project(null, null), List.of(), enormous, false);

            assertThat(prompt).contains("(truncated)");
            assertThat(prompt.length()).isLessThan(enormous.length());
        }

        @Test
        void survives_a_project_with_nothing_attached_yet() {
            String prompt = LocalClaudeChat.prompt(project(null, null), List.of(), "what is this?", false);

            assertThat(prompt).contains("none attached");
            assertThat(prompt).contains("what is this?");
        }
    }
}
