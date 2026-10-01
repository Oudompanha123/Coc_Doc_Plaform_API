package com.coc.docs.axrenewal;

import static org.assertj.core.api.Assertions.assertThat;

import com.coc.docs.axrenewal.AxRenewalGenerationProgress.Activity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * One stream-json line into what the model did, in words (ADR-082). Only
 * what the stream says; nothing from a file's contents; never an absolute
 * path of this machine.
 *
 * <p>// req: ADR-082
 */
class AxRenewalStreamEventsTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Instant AT = Instant.parse("2026-10-01T09:00:00Z");

    @TempDir
    Path staging;

    private List<Activity> parse(String event) throws Exception {
        return AxRenewalStreamEvents.parse(JSON.readTree(event), staging, AT);
    }

    private String inStaging(String rel) {
        return JSON.getNodeFactory().textNode(staging.resolve(rel).toString()).toString();
    }

    private static String toolUse(String name, String input) {
        return "{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"tool_use\",\"name\":\"" + name
                + "\",\"input\":" + input + "}]}}";
    }

    @Test
    void the_session_starting_is_a_note() throws Exception {
        assertThat(parse("{\"type\":\"system\",\"subtype\":\"init\",\"cwd\":\"C:/secret/place\"}"))
                .singleElement()
                .satisfies(a -> {
                    assertThat(a.kind()).isEqualTo("note");
                    assertThat(a.text()).isEqualTo("Connected to the local model — starting work");
                    assertThat(a.at()).isEqualTo(AT);
                });
    }

    @Test
    void a_read_names_the_file_relative_to_the_project() throws Exception {
        assertThat(parse(toolUse("Read", "{\"file_path\":" + inStaging("src/pages/Home.tsx") + "}")))
                .singleElement()
                .satisfies(a -> {
                    assertThat(a.kind()).isEqualTo("read");
                    assertThat(a.text()).isEqualTo("Reading src/pages/Home.tsx");
                    assertThat(a.path()).isEqualTo("src/pages/Home.tsx");
                });
    }

    @Test
    void a_path_outside_the_project_is_shown_by_its_file_name_alone() throws Exception {
        String elsewhere = JSON.getNodeFactory()
                .textNode(staging.getParent().resolve("someone-else").resolve("notes.md").toString())
                .toString();

        assertThat(parse(toolUse("Read", "{\"file_path\":" + elsewhere + "}")))
                .singleElement()
                .satisfies(a -> {
                    assertThat(a.text()).isEqualTo("Reading notes.md");
                    assertThat(a.text()).doesNotContain(staging.getParent().toString());
                });
    }

    @Test
    void edits_and_writes_are_changes() throws Exception {
        assertThat(parse(toolUse("Edit", "{\"file_path\":" + inStaging("src/App.tsx") + ",\"old_string\":\"a\"}")))
                .singleElement()
                .satisfies(a -> {
                    assertThat(a.kind()).isEqualTo("edit");
                    assertThat(a.text()).isEqualTo("Editing src/App.tsx");
                });
        assertThat(parse(toolUse("Write", "{\"file_path\":" + inStaging("src/new.ts") + ",\"content\":\"secret\"}")))
                .singleElement()
                .satisfies(a -> {
                    assertThat(a.kind()).isEqualTo("write");
                    assertThat(a.text()).isEqualTo("Writing src/new.ts").doesNotContain("secret");
                });
    }

    @Test
    void searches_say_what_was_looked_for() throws Exception {
        assertThat(parse(toolUse("Grep", "{\"pattern\":\"useState\"}")))
                .extracting(Activity::text)
                .containsExactly("Searching for “useState”");
        assertThat(parse(toolUse("Glob", "{\"pattern\":\"**/*.tsx\"}")))
                .extracting(Activity::text)
                .containsExactly("Looking for files matching **/*.tsx");
    }

    @Test
    void a_plan_update_counts_its_own_steps() throws Exception {
        String todos = "{\"todos\":["
                + "{\"content\":\"Port the header\",\"status\":\"completed\",\"activeForm\":\"Porting the header\"},"
                + "{\"content\":\"Port the footer\",\"status\":\"in_progress\",\"activeForm\":\"Porting the footer\"},"
                + "{\"content\":\"Port the menu\",\"status\":\"pending\",\"activeForm\":\"Porting the menu\"}]}";

        assertThat(parse(toolUse("TodoWrite", todos)))
                .singleElement()
                .satisfies(a -> {
                    assertThat(a.kind()).isEqualTo("plan");
                    assertThat(a.text()).isEqualTo("Updated its plan — 1 of 3 steps done; now: Porting the footer");
                });
    }

    @Test
    void an_unknown_tool_is_named_not_guessed_at() throws Exception {
        assertThat(parse(toolUse("SomethingNew", "{}")))
                .singleElement()
                .satisfies(a -> {
                    assertThat(a.kind()).isEqualTo("tool");
                    assertThat(a.text()).isEqualTo("Using SomethingNew");
                });
    }

    @Test
    void the_model_s_own_words_are_a_clipped_note() throws Exception {
        String longText = "I will port the pages.   " + "x".repeat(400);

        assertThat(parse("{\"type\":\"assistant\",\"message\":{\"content\":["
                        + "{\"type\":\"thinking\",\"thinking\":\"private\"},"
                        + "{\"type\":\"text\",\"text\":" + JSON.getNodeFactory().textNode(longText) + "}]}}"))
                .singleElement()
                .satisfies(a -> {
                    assertThat(a.kind()).isEqualTo("note");
                    assertThat(a.text()).startsWith("I will port the pages. x").endsWith("…");
                    assertThat(a.text()).hasSize(AxRenewalStreamEvents.NOTE_LIMIT);
                });
    }

    @Test
    void a_tool_s_result_is_never_shown_only_its_failure() throws Exception {
        assertThat(parse("{\"type\":\"user\",\"message\":{\"content\":["
                        + "{\"type\":\"tool_result\",\"content\":\"the whole file's contents\"}]}}"))
                .isEmpty();

        assertThat(parse("{\"type\":\"user\",\"message\":{\"content\":["
                        + "{\"type\":\"tool_result\",\"is_error\":true,\"content\":\"File does not exist.\"}]}}"))
                .singleElement()
                .satisfies(a -> {
                    assertThat(a.kind()).isEqualTo("error");
                    assertThat(a.text()).isEqualTo("A step failed: File does not exist.");
                });
    }

    @Test
    void the_closing_result_says_how_it_ended() throws Exception {
        assertThat(parse("{\"type\":\"result\",\"subtype\":\"success\",\"num_turns\":12,\"result\":\"ok\"}"))
                .singleElement()
                .satisfies(a -> {
                    assertThat(a.kind()).isEqualTo("done");
                    assertThat(a.text()).isEqualTo("The model finished its work in 12 turns");
                });
        assertThat(parse("{\"type\":\"result\",\"subtype\":\"error_max_turns\",\"is_error\":true}"))
                .singleElement()
                .satisfies(a -> {
                    assertThat(a.kind()).isEqualTo("error");
                    assertThat(a.text()).isEqualTo("The model stopped without finishing (error_max_turns)");
                });
    }

    @Test
    void anything_unrecognised_produces_nothing() throws Exception {
        assertThat(parse("{\"type\":\"stream_event\",\"event\":{}}")).isEmpty();
        assertThat(parse("{}")).isEmpty();
        JsonNode notAnObject = JSON.readTree("[1,2]");
        assertThat(AxRenewalStreamEvents.parse(notAnObject, staging, AT)).isEmpty();
        assertThat(AxRenewalStreamEvents.parse(null, staging, AT)).isEmpty();
    }
}
