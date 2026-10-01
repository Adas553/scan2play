package com.scan2play.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RequestModeTest {

    @Test
    void theFormsField_isReadAsAMode_andAnythingElseIsASong() {
        assertThat(RequestMode.fromParam("MOOD")).isEqualTo(RequestMode.MOOD);
        assertThat(RequestMode.fromParam(" mood ")).isEqualTo(RequestMode.MOOD);
        assertThat(RequestMode.fromParam("SONG")).isEqualTo(RequestMode.SONG);
        assertThat(RequestMode.fromParam(null)).as("an old page without the field").isEqualTo(RequestMode.SONG);
        assertThat(RequestMode.fromParam("dance")).isEqualTo(RequestMode.SONG);
    }
}
