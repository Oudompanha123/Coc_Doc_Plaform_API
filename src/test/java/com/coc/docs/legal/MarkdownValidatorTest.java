package com.coc.docs.legal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The Markdown validator a legal page's plain-language box and every
 * section body are checked against — mirrors the frontend's {@code
 * markdown.ts} {@code validateMarkdown} (ADR-012 §3: that copy is advisory
 * only, this is what a write path actually enforces).
 */
class MarkdownValidatorTest {

    private final MarkdownValidator validator = new MarkdownValidator();

    @Test
    void a_well_formed_string_within_the_limit_passes() {
        assertThat(validator.validate("Hello **world**.", 100, "Body")).isNull();
    }

    @Test
    void null_is_rejected() {
        assertThat(validator.validate(null, 100, "Body")).isNotNull();
    }

    @Test
    void blank_is_rejected() {
        assertThat(validator.validate("   ", 100, "Body")).isNotNull();
    }

    @Test
    void over_the_length_limit_is_rejected() {
        assertThat(validator.validate("x".repeat(101), 100, "Body")).isNotNull();
    }

    @Test
    void exactly_at_the_length_limit_passes() {
        assertThat(validator.validate("x".repeat(100), 100, "Body")).isNull();
    }

    @Test
    void a_script_tag_is_rejected() {
        assertThat(validator.validate("<script>alert(1)</script>", 100, "Body")).isNotNull();
    }

    @Test
    void an_inline_html_tag_is_rejected() {
        assertThat(validator.validate("plain <b>bold</b> text", 100, "Body")).isNotNull();
    }

    @Test
    void an_https_link_passes() {
        assertThat(validator.validate("[click](https://example.com)", 100, "Body")).isNull();
    }

    @Test
    void a_mailto_link_passes() {
        assertThat(validator.validate("[email](mailto:a@b.com)", 100, "Body")).isNull();
    }

    @Test
    void a_javascript_link_is_rejected() {
        assertThat(validator.validate("[click](javascript:alert(1))", 100, "Body")).isNotNull();
    }

    @Test
    void a_same_origin_relative_link_is_allowed() {
        assertThat(validator.validate("[click](/internal)", 100, "Body")).isNull();
    }

    @Test
    void a_scheme_relative_link_is_rejected() {
        assertThat(validator.validate("[click](//evil.com)", 100, "Body")).isNotNull();
    }

    @Test
    void a_link_whose_url_contains_balanced_parens_is_parsed_correctly() {
        assertThat(validator.validate("[wiki](https://en.wikipedia.org/wiki/Foo_(bar))", 100, "Body")).isNull();
        assertThat(validator.validate("[bad](javascript:alert(1))", 100, "Body")).isNotNull();
    }

    @Test
    void isAllowedLinkHref_accepts_https_mailto_and_a_relative_path() {
        assertThat(MarkdownValidator.isAllowedLinkHref("https://example.com")).isTrue();
        assertThat(MarkdownValidator.isAllowedLinkHref("mailto:a@b.com")).isTrue();
        assertThat(MarkdownValidator.isAllowedLinkHref("/privacy")).isTrue();
        assertThat(MarkdownValidator.isAllowedLinkHref("/docs/get-started")).isTrue();
    }

    @Test
    void isAllowedLinkHref_rejects_everything_else_including_a_scheme_relative_url() {
        assertThat(MarkdownValidator.isAllowedLinkHref("http://example.com")).isFalse();
        assertThat(MarkdownValidator.isAllowedLinkHref("javascript:alert(1)")).isFalse();
        assertThat(MarkdownValidator.isAllowedLinkHref("//evil.com")).isFalse();
        assertThat(MarkdownValidator.isAllowedLinkHref(null)).isFalse();
    }
}
