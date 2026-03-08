    package com.scan2play.config;

    import com.google.genai.Client;
    import org.springframework.beans.factory.annotation.Value;
    import org.springframework.context.annotation.Bean;
    import org.springframework.context.annotation.Configuration;
    import com.fasterxml.jackson.databind.ObjectMapper;

    @Configuration
    public class GeminiConfig {

        @Value("${google.ai.api-key}")
        private String apiKey;

        @Bean
        public ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        /**
         * Creates and configures the Gemini AI client.
         *
         * @return configured Client instance ready to make API calls
         * @throws RuntimeException if the API key is missing or empty
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
                    .build();
        }
    }
