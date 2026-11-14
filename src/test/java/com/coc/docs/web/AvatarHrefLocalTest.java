package com.coc.docs.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** A photo stored on local disk is a bare file name in the column (ADR-119). */
class AvatarHrefLocalTest {

    @Test
    void aStoredFileNameBecomesTheImageRoute() {
        assertThat(ApiDtos.avatarHref("0f8fad5b-d9cb-469f-a165-70867728950e.png"))
                .isEqualTo("/api/v1/image/0f8fad5b-d9cb-469f-a165-70867728950e.png");
    }

    @Test
    void anythingElseIsLeftAsItWas() {
        assertThat(ApiDtos.avatarHref("https://lh3.googleusercontent.com/a/photo.png"))
                .isEqualTo("https://lh3.googleusercontent.com/a/photo.png");
        assertThat(ApiDtos.avatarHref(null)).isNull();
    }
}
