package com.scan2play.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genai.Client;
import com.google.genai.types.HttpOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class GeminiConfig {

    /**
     * How long one Gemini call may take (milliseconds). Without it a hanging call held its thread for as long as the
     * connection lived — after the guest's own request had long timed out (spring.mvc.async.request-timeout, 30 s) — and a
     * few of them used up the threads that evaluate every guest's request. A failed call sends the request on to the DJ
     * unchecked ({@code SongEvaluationService.withoutTheAi}).
     */
    static final int TIMEOUT_MS = 10_000;

    @Value("${google.ai.api-key}")
    private String apiKey;

    /** Reads the AI's JSON answers ({@code SongEvaluationService}); unknown fields are ignored by {@code DjResponse} itself. */
    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }

    /**
     * Creates and configures the Gemini AI client.
     *
     * @return configured Client instance ready to make API calls
     * @throws IllegalStateException if the API key is missing or empty
     */
    @Bean
    public Client geminiClient() {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "Gemini API key is not configured. " +
                            "Make sure the GOOGLE_AI_API_KEY environment variable is set."
            );
        }

        return Client.builder()
                .apiKey(apiKey)
                .httpOptions(HttpOptions.builder().timeout(TIMEOUT_MS).build())
                .build();
    }
}
