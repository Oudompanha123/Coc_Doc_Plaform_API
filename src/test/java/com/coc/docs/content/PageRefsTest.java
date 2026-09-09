package com.coc.docs.content;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Tests for internal-reference collection.
 *
 * <p>This is what makes FR-CONT-007 answerable: because links store a page id
 * rather than a URL, "what links here?" is exact. A reference missed by this
 * walk is a link the delete check would not see, so the nested cases matter as
 * much as the simple ones.
 *
 * <p>// req: FR-CONT-007, FR-READ-003
 */
class PageRefsTest {

    private final ObjectMapper json = new ObjectMapper();

    private static final UUID A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID B = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private java.util.Set<UUID> collect(String raw) {
        try {
            return PageRefs.collect(json.readTree(raw));
        } catch (Exception e) {
            throw new AssertionError("the test fixture itself is not valid JSON", e);
        }
    }

    @Test
    void an_inline_link_in_a_paragraph_is_found() {
        assertThat(collect(
                        "[{\"type\":\"paragraph\",\"text\":[{\"text\":\"see\",\"link\":"
                                + "{\"pageId\":\"" + A + "\"}}]}]"))
                .containsExactly(A);
    }

    @Test
    void references_inside_nested_blocks_are_found() {
        // A reference two levels down is still a broken link if the target is
        // deleted, so the walk has to reach it.
        String nested = "[{\"type\":\"callout\",\"variant\":\"note\",\"body\":"
                + "[{\"type\":\"steps\",\"items\":[{\"title\":[],\"body\":"
                + "[{\"type\":\"paragraph\",\"text\":[{\"text\":\"x\",\"link\":"
                + "{\"pageId\":\"" + A + "\"}}]}]}]}]}]";

        assertThat(collect(nested)).containsExactly(A);
    }

    @Test
    void table_cells_and_card_links_are_both_found() {
        String mixed = "[{\"type\":\"table\",\"headers\":[[]],\"rows\":"
                + "[[[{\"text\":\"a\",\"link\":{\"pageId\":\"" + A + "\"}}]]]},"
                + "{\"type\":\"cards\",\"items\":[{\"title\":[],\"body\":[],\"link\":"
                + "{\"pageId\":\"" + B + "\"}}]}]";

        assertThat(collect(mixed)).containsExactlyInAnyOrder(A, B);
    }

    @Test
    void a_repeated_reference_is_reported_once() {
        String twice = "[{\"type\":\"paragraph\",\"text\":[{\"text\":\"a\",\"link\":"
                + "{\"pageId\":\"" + A + "\"}},{\"text\":\"b\",\"link\":"
                + "{\"pageId\":\"" + A + "\"}}]}]";

        assertThat(collect(twice)).containsExactly(A);
    }

    @Test
    void an_external_href_is_not_an_internal_reference() {
        assertThat(collect(
                        "[{\"type\":\"paragraph\",\"text\":[{\"text\":\"x\",\"link\":"
                                + "{\"href\":\"https://example.com\"}}]}]"))
                .isEmpty();
    }

    @Test
    void a_non_uuid_page_id_is_skipped_rather_than_thrown() {
        // This is a read-side scan over already-stored content. A malformed
        // reference is a reporting gap; throwing would stop the delete check from
        // returning the references it did find.
        assertThat(collect(
                        "[{\"type\":\"paragraph\",\"text\":[{\"text\":\"x\",\"link\":"
                                + "{\"pageId\":\"not-a-uuid\"}}]}]"))
                .isEmpty();
    }

    @Test
    void a_malformed_body_yields_no_references_rather_than_failing() {
        assertThat(PageRefs.collect(null)).isEmpty();
        assertThat(collect("{}")).isEmpty();
        assertThat(collect("[{\"type\":\"unknown\"}]")).isEmpty();
    }
}
