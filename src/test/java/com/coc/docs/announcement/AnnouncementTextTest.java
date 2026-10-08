package com.coc.docs.announcement;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * An announcement's Markdown, flattened for a push banner (ADR-103).
 *
 * <p>// req: ADR-102, ADR-103
 */
class AnnouncementTextTest {

    @Test
    @DisplayName("keeps the words and drops every marker the editor writes")
    void stripsMarkdown() {
        String body = String.join("\n",
                "## **Portal** maintenance",
                "",
                "The portal is _offline_ from `22:00`, [details](https://status.example.com).",
                "",
                "- Save your work",
                "1. Sign out",
                "> Thanks for your [patience]{color=red}.",
                "",
                "---",
                "```",
                "npm **run** build",
                "```");

        assertThat(AnnouncementText.plainText(body)).isEqualTo(String.join("\n",
                "Portal maintenance",
                "The portal is offline from 22:00, details.",
                "Save your work",
                "Sign out",
                "Thanks for your patience.",
                "npm **run** build"));
    }

    @Test
    @DisplayName("leaves snake_case and escaped markers as written")
    void keepsLiterals() {
        assertThat(AnnouncementText.plainText("use snake_case_names and \\*stars\\*")).isEqualTo("use snake_case_names and *stars*");
    }

    @Test
    @DisplayName("puts a multi-line body on one line, since a Windows banner shows only the first")
    void excerptIsOneLine() {
        assertThat(AnnouncementText.excerpt("Dear Yuth,\n\nThe **portal** is down tonight.\n- Save your work", 240))
                .isEqualTo("Dear Yuth, The portal is down tonight. Save your work");
    }

    @Test
    @DisplayName("cuts a long body at a word, with an ellipsis")
    void excerptCutsAtAWord() {
        String excerpt = AnnouncementText.excerpt("alpha beta gamma delta epsilon", 20);

        assertThat(excerpt).isEqualTo("alpha beta gamma…");
        assertThat(excerpt.length()).isLessThanOrEqualTo(20);
    }

    @Test
    @DisplayName("leaves a short body whole, and an empty one empty")
    void excerptShortAndEmpty() {
        assertThat(AnnouncementText.excerpt("**Short**", 240)).isEqualTo("Short");
        assertThat(AnnouncementText.excerpt("", 240)).isEmpty();
        assertThat(AnnouncementText.excerpt(null, 240)).isEmpty();
    }
}
