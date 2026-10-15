package com.scan2play.config;

import com.google.genai.Client;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.ThinkingConfig;
import com.google.genai.types.ThinkingLevel;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GeminiConfigTest {

    /** A Gemini on this machine: the real SDK talks to it, so an upgrade of {@code google-genai} that changes these shows here. */
    private HttpServer gemini;
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private final CountDownLatch released = new CountDownLatch(1);

    @BeforeEach
    void startGemini() throws IOException {
        gemini = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        gemini.start();
    }

    @AfterEach
    void stopGemini() {
        released.countDown();
        gemini.stop(0);
    }

    /** A slow Gemini: one try of 15 s, then the request goes to the DJ unchecked — not a retry that holds the guest to 30 s. */
    @Test
    void oneCall_isOneTry_of15Seconds() {
        HttpOptions options = GeminiConfig.httpOptions();

        assertThat(options.timeout()).contains(15_000);
        assertThat(options.retryOptions().flatMap(retry -> retry.attempts())).contains(1);
    }

    /**
     * The model asked by default is one Google still serves: gemini-3.5-flash was retired on 2026-10-08 (its calls go to 3.6), and the
     * comparison of 2026-10-15 chose gemini-3.6-flash ({@code GeminiComparison}). Another model is GOOGLE_AI_MODEL, not this line.
     */
    @Test
    void theDefaultModel_isGemini36Flash() throws IOException {
        java.util.Properties properties = new java.util.Properties();
        try (var in = getClass().getResourceAsStream("/application.properties")) {
            properties.load(in);
        }

        assertThat(properties.getProperty("google.ai.model-name")).isEqualTo("${GOOGLE_AI_MODEL:gemini-3.6-flash}");
    }

    /** The SDK retries a 503 by default: with the config's options it asks once. */
    @Test
    void anOverloadedGemini_isAskedOnce() {
        answer(503, "{\"error\":{\"code\":503,\"message\":\"overloaded\",\"status\":\"UNAVAILABLE\"}}");

        assertThatThrownBy(() -> client(GeminiConfig.httpOptions()).models.generateContent("gemini-3.6-flash", "hi", null))
                .isInstanceOf(RuntimeException.class);
        assertThat(calls).hasValue(1);
    }

    /** A Gemini that never answers: the call ends at the timeout, once (the config's 15 s shortened here to 500 ms). */
    @Test
    void aHangingGemini_endsAtTheTimeout_once() {
        gemini.createContext("/", exchange -> {
            calls.incrementAndGet();
            try {
                released.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
        });
        HttpOptions shortened = GeminiConfig.httpOptions().toBuilder().timeout(500).build();

        long start = System.nanoTime();
        assertThatThrownBy(() -> client(shortened).models.generateContent("gemini-3.6-flash", "hi", null))
                .isInstanceOf(RuntimeException.class);
        long tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertThat(tookMs).isLessThan(5_000);
        assertThat(calls).hasValue(1);
    }

    /** What {@code SongEvaluationService} sends (JSON mode, a thinking level) reaches Gemini, and its answer's text is read. */
    @Test
    void theAnswersText_isRead_andTheConfigIsSent() {
        answer(200, """
                {"candidates":[{"content":{"role":"model","parts":[{"text":"{\\"decision\\":\\"ACCEPTED\\"}"}]},
                "finishReason":"STOP"}]}""");
        GenerateContentConfig config = GenerateContentConfig.builder()
                .responseMimeType("application/json")
                .thinkingConfig(ThinkingConfig.builder().thinkingLevel(new ThinkingLevel(ThinkingLevel.Known.LOW)).build())
                .build();

        String text = client(GeminiConfig.httpOptions()).models.generateContent("gemini-3.6-flash", "hi", config).text();

        assertThat(text).isEqualTo("{\"decision\":\"ACCEPTED\"}");
        assertThat(lastBody.get()).contains("\"responseMimeType\":\"application/json\"").contains("\"thinkingLevel\":\"LOW\"");
    }

    private void answer(int status, String json) {
        gemini.createContext("/", exchange -> {
            calls.incrementAndGet();
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
    }

    private Client client(HttpOptions options) {
        return Client.builder()
                .apiKey("test-key")
                .httpOptions(options.toBuilder().baseUrl("http://127.0.0.1:" + gemini.getAddress().getPort()).build())
                .build();
    }
}
