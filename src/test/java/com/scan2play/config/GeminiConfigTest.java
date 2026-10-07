package com.scan2play.config;

import com.google.genai.types.HttpOptions;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GeminiConfigTest {

    /** A slow Gemini: one try of 15 s, then the request goes to the DJ unchecked — not a retry that holds the guest to 30 s. */
    @Test
    void oneCall_isOneTry_of15Seconds() {
        HttpOptions options = GeminiConfig.httpOptions();

        assertThat(options.timeout()).contains(15_000);
        assertThat(options.retryOptions().flatMap(retry -> retry.attempts())).contains(1);
    }
}
