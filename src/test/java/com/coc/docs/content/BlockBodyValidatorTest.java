package com.coc.docs.content;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for the authoritative block-model validator.
 *
 * <p>The security cases are not an afterthought here. This validator plus the
 * absence of a raw-html block type is the entire defence against stored XSS
 * (ADR-010 section 3), so a gap in it is not a correctness bug — it is the
 * vulnerability.
 *
 * <p>// req: FR-CONT-009, NFR-SEC-SANITIZE, ADR-011, ADR-012
 */
class BlockBodyValidatorTest {

    private final ObjectMapper json = new ObjectMapper();
    private final BlockBodyValidator validator = new BlockBodyValidator();

    private JsonNode body(String raw) {
        try {
            return json.readTree(raw);
        } catch (Exception e) {
            throw new AssertionError("the test fixture itself is not valid JSON", e);
        }
    }

    private ValidationResult validate(String raw) {
        return validator.validate(body(raw));
    }

    private List<String> paths(ValidationResult result) {
        return result.issues().stream().map(ValidationIssue::path).toList();
    }

    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("the body envelope")
    class Envelope {

        @Test
        void an_empty_array_is_valid() {
            // A page with no blocks yet is a legitimate state: an editor creates
            // the page before writing anything into it.
            assertThat(validate("[]").valid()).isTrue();
        }

        @Test
        void an_object_is_not_a_body() {
            ValidationResult result = validate("{\"type\":\"paragraph\"}");
            assertThat(result.valid()).isFalse();
            assertThat(result.issues())
                    .containsExactly(new ValidationIssue("body", "body must be an array of blocks"));
        }

        @Test
        void a_null_body_is_rejected_without_throwing() {
            ValidationResult result = validator.validate(null);
            assertThat(result.valid()).isFalse();
            assertThat(paths(result)).containsExactly("body");
        }

        @Test
        void too_many_blocks_is_rejected_and_names_both_numbers() {
            StringBuilder blocks = new StringBuilder("[");
            for (int i = 0; i <= BlockBodyValidator.MAX_BLOCKS_PER_PAGE; i++) {
                blocks.append(i == 0 ? "" : ",").append("{\"type\":\"paragraph\",\"text\":[]}");
            }
            blocks.append("]");

            ValidationResult result = validate(blocks.toString());
            assertThat(result.valid()).isFalse();
            assertThat(result.issues().getFirst().message())
                    .contains("501")
                    .contains(String.valueOf(BlockBodyValidator.MAX_BLOCKS_PER_PAGE));
        }
    }

    @Nested
    @DisplayName("the block vocabulary")
    class Vocabulary {

        @Test
        void an_unknown_type_names_the_offending_value_and_the_vocabulary() {
            ValidationResult result = validate("[{\"type\":\"iframe\"}]");
            assertThat(result.valid()).isFalse();

            ValidationIssue issue = result.issues().getFirst();
            assertThat(issue.path()).isEqualTo("body[0].type");
            // Naming the value matters: an editor pasting content needs to know
            // which construct was refused, not merely that something was.
            assertThat(issue.message()).contains("\"iframe\"").contains("paragraph");
        }

        @Test
        void there_is_no_raw_html_block_type() {
            // The structural defence from ADR-010 section 3. If this ever passes,
            // stored XSS is reachable again and the sanitisation argument
            // collapses, so the test asserts the absence directly.
            assertThat(BlockType.fromWire("html")).isNull();
            assertThat(BlockType.fromWire("raw")).isNull();
            assertThat(BlockType.fromWire("rawHtml")).isNull();
            assertThat(validate("[{\"type\":\"html\",\"source\":\"<script>x()</script>\"}]").valid())
                    .isFalse();
        }

        @Test
        void the_vocabulary_is_exactly_ten_types() {
            // CONST-TECH-04 caps the vocabulary at 8-10. Every type costs both an
            // editing UI and a renderer, so growth is a decision, not a drift.
            assertThat(BlockType.values()).hasSize(10);
        }
    }

    @Nested
    @DisplayName("links")
    class Links {

        @ParameterizedTest
        @ValueSource(strings = {
            "javascript:alert(1)",
            "JavaScript:alert(1)",
            "  javascript:alert(1)",
            "data:text/html;base64,PHNjcmlwdD5hbGVydCgxKTwvc2NyaXB0Pg==",
            "vbscript:msgbox(1)",
            "file:///etc/passwd",
            "//evil.example.com/phish"
        })
        void a_dangerous_scheme_is_refused_at_persistence(String href) {
            // Rejected here rather than escaped at render: a body that cannot
            // contain a dangerous href does not depend on every renderer, now and
            // in future, remembering to escape it.  // req: NFR-SEC-SANITIZE
            ValidationResult result = validate(
                    "[{\"type\":\"paragraph\",\"text\":[{\"text\":\"click\",\"link\":{\"href\":\""
                            + href + "\"}}]}]");

            assertThat(result.valid()).as("href %s must be refused", href).isFalse();
        }

        @ParameterizedTest
        @ValueSource(strings = {
            "/en/docs/jex2.0/usage",
            "https://example.com/page",
            "http://example.com/page"
        })
        void a_safe_href_is_accepted(String href) {
            ValidationResult result = validate(
                    "[{\"type\":\"paragraph\",\"text\":[{\"text\":\"click\",\"link\":{\"href\":\""
                            + href + "\"}}]}]");

            assertThat(result.valid()).as("href %s must be accepted", href).isTrue();
        }

        @Test
        void a_protocol_relative_url_is_not_treated_as_relative() {
            // "//host" looks relative and is not: it inherits the page scheme and
            // points off-site.
            assertThat(BlockBodyValidator.isSafeHref("//evil.example.com")).isFalse();
            assertThat(BlockBodyValidator.isSafeHref("/en/docs")).isTrue();
        }

        @Test
        void a_link_must_carry_exactly_one_of_pageId_or_href() {
            String both = "[{\"type\":\"paragraph\",\"text\":[{\"text\":\"x\",\"link\":"
                    + "{\"pageId\":\"p1\",\"href\":\"/a\"}}]}]";
            String neither = "[{\"type\":\"paragraph\",\"text\":[{\"text\":\"x\",\"link\":{}}]}]";

            assertThat(validate(both).valid()).isFalse();
            assertThat(validate(neither).valid()).isFalse();
            assertThat(validate(both).issues().getFirst().message())
                    .isEqualTo("link must have exactly one of pageId or href");
        }
    }

    @Nested
    @DisplayName("nesting")
    class Nesting {

        @Test
        void nesting_within_the_limit_is_accepted() {
            String twoDeep = "[{\"type\":\"callout\",\"variant\":\"note\",\"body\":"
                    + "[{\"type\":\"callout\",\"variant\":\"note\",\"body\":"
                    + "[{\"type\":\"paragraph\",\"text\":[]}]}]}]";

            assertThat(validate(twoDeep).valid()).isTrue();
        }

        @Test
        void nesting_beyond_the_limit_is_refused_rather_than_recursed() {
            // The bound exists so a hostile or accidental deep structure cannot
            // exhaust the renderer. A validator that recursed without a limit
            // would be the denial of service it is meant to prevent.
            StringBuilder deep = new StringBuilder();
            int depth = BlockBodyValidator.MAX_NESTING_DEPTH + 2;
            for (int i = 0; i < depth; i++) {
                deep.append("[{\"type\":\"callout\",\"variant\":\"note\",\"body\":");
            }
            deep.append("[]");
            for (int i = 0; i < depth; i++) {
                deep.append("}]");
            }

            ValidationResult result = validate(deep.toString());
            assertThat(result.valid()).isFalse();
            assertThat(result.issues())
                    .anySatisfy(issue -> assertThat(issue.message())
                            .contains("nesting exceeds the maximum depth of "
                                    + BlockBodyValidator.MAX_NESTING_DEPTH));
        }
    }

    @Nested
    @DisplayName("per-block rules")
    class PerBlock {

        @Test
        void a_heading_is_limited_to_levels_two_and_three() {
            // h1 belongs to the page title, which is a column, not a block.
            assertThat(validate("[{\"type\":\"heading\",\"level\":1,\"text\":[]}]").valid())
                    .isFalse();
            assertThat(validate("[{\"type\":\"heading\",\"level\":2,\"text\":[]}]").valid())
                    .isTrue();
            assertThat(validate("[{\"type\":\"heading\",\"level\":4,\"text\":[]}]").valid())
                    .isFalse();
        }

        @Test
        void a_ragged_table_is_refused_with_both_widths_named() {
            String ragged = "[{\"type\":\"table\",\"headers\":[[],[]],\"rows\":[[[],[],[]]]}]";

            ValidationResult result = validate(ragged);
            assertThat(result.valid()).isFalse();
            assertThat(result.issues().getFirst().message())
                    .isEqualTo("row has 3 cells but the table has 2 columns");
        }

        @Test
        void a_code_block_requires_its_copyable_flag() {
            // copyable carries behaviour, not styling: the reader-side copy button
            // attaches to it, and 183 such blocks exist in the current content. A
            // default would silently drop the behaviour on migration.
            assertThat(validate(
                            "[{\"type\":\"code\",\"language\":\"bash\",\"source\":\"ls\"}]")
                            .valid())
                    .isFalse();
            assertThat(validate(
                            "[{\"type\":\"code\",\"language\":\"bash\",\"source\":\"ls\","
                                    + "\"copyable\":true}]")
                            .valid())
                    .isTrue();
        }

        @Test
        void an_image_requires_alt_but_accepts_an_empty_one() {
            // Empty alt is the correct markup for a decorative image; a missing
            // alt field is an accessibility defect. The two are not the same, so
            // the validator does not treat them the same.
            assertThat(validate("[{\"type\":\"image\",\"assetId\":\"a1\"}]").valid()).isFalse();
            assertThat(validate("[{\"type\":\"image\",\"assetId\":\"a1\",\"alt\":\"\"}]").valid())
                    .isTrue();
        }

        @Test
        void an_embed_may_only_name_a_component_from_the_closed_registry() {
            // An editor may place a component; an editor may not define one. This
            // is what keeps Tier B content working without turning the CMS into an
            // arbitrary code-execution surface.  // req: FR-CONT-010
            assertThat(validate("[{\"type\":\"embed\",\"component\":\"HarnessDemo\"}]").valid())
                    .isTrue();
            assertThat(validate("[{\"type\":\"embed\",\"component\":\"ArbitraryThing\"}]").valid())
                    .isFalse();
        }

        @Test
        void an_unknown_inline_mark_is_refused() {
            ValidationResult result = validate(
                    "[{\"type\":\"paragraph\",\"text\":[{\"text\":\"x\",\"marks\":[\"blink\"]}]}]");

            assertThat(result.valid()).isFalse();
            assertThat(paths(result)).containsExactly("body[0].text[0].marks[0]");
        }

        @Test
        void a_json_null_is_treated_as_an_absent_optional_field() {
            // Matches `undefined` on the TypeScript side. Without this, a client
            // that serialises absent optionals as null would be refused for no
            // reason an editor could act on.
            assertThat(validate(
                            "[{\"type\":\"paragraph\",\"text\":[{\"text\":\"x\",\"marks\":null}]}]")
                            .valid())
                    .isTrue();
            assertThat(validate(
                            "[{\"type\":\"image\",\"assetId\":\"a\",\"alt\":\"\",\"caption\":null}]")
                            .valid())
                    .isTrue();
        }
    }

    @Nested
    @DisplayName("issue reporting")
    class Reporting {

        @Test
        void every_problem_is_reported_not_only_the_first() {
            // An editor fixing one error at a time through a save-reject loop is
            // the usability failure NFR-USE-01 exists to prevent.
            ValidationResult result = validate(
                    "[{\"type\":\"heading\",\"level\":9,\"text\":\"not rich text\"},"
                            + "{\"type\":\"list\",\"ordered\":\"yes\",\"items\":{}}]");

            assertThat(result.valid()).isFalse();
            assertThat(paths(result))
                    .containsExactly(
                            "body[0].level",
                            "body[0].text",
                            "body[1].ordered",
                            "body[1].items");
        }

        @Test
        void a_failed_result_always_carries_at_least_one_issue() {
            // Otherwise a caller could report a refusal it cannot explain.
            ValidationResult result = validate("[{\"type\":\"nope\"}]");
            assertThat(result.valid()).isFalse();
            assertThat(result.issues()).isNotEmpty();
        }

        @Test
        void paths_point_at_the_offending_node_inside_nested_structures() {
            ValidationResult result = validate(
                    "[{\"type\":\"steps\",\"items\":[{\"title\":[],\"body\":"
                            + "[{\"type\":\"paragraph\",\"text\":123}]}]}]");

            assertThat(paths(result)).containsExactly("body[0].items[0].body[0].text");
        }
    }
}
