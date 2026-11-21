package com.scan2play.deck;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

/**
 * Scan2Play's two calls for the program (DeviceApiController): the panel's code swapped for the program's token, and the songs that
 * played, answered with the requests marked. Nothing else is sent.
 */
class ServerClient {

    /** What a call came to. */
    enum Outcome { OK, WRONG_CODE, DISCONNECTED, TOO_MANY, FAILED }

    record Answer(Outcome outcome, String token, List<String> marked) {
    }

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private final String userAgent;

    ServerClient(String version) {
        this.userAgent = "Scan2Play-Zagrane/" + version;
    }

    Answer pair(String server, String code, String computer) {
        String body = "{\"code\":" + Json.quote(code) + ",\"name\":" + Json.quote(computer) + "}";
        try {
            HttpResponse<String> response = send(server, "/api/device/pair", body, null);
            return switch (response.statusCode()) {
                case 200 -> Json.string(response.body(), "token").map(token -> new Answer(Outcome.OK, token, List.of()))
                        .orElse(new Answer(Outcome.FAILED, null, List.of()));
                case 400 -> new Answer(Outcome.WRONG_CODE, null, List.of());
                case 429 -> new Answer(Outcome.TOO_MANY, null, List.of());
                default -> new Answer(Outcome.FAILED, null, List.of());
            };
        } catch (IOException | IllegalArgumentException e) {
            return new Answer(Outcome.FAILED, null, List.of());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Answer(Outcome.FAILED, null, List.of());
        }
    }

    Answer plays(String server, String token, List<Played> songs) {
        try {
            HttpResponse<String> response = send(server, "/api/device/plays", Json.plays(songs), token);
            return switch (response.statusCode()) {
                case 200 -> new Answer(Outcome.OK, token, Json.strings(response.body(), "marked"));
                case 401 -> new Answer(Outcome.DISCONNECTED, null, List.of());
                case 429 -> new Answer(Outcome.TOO_MANY, token, List.of());
                case 400 -> new Answer(Outcome.OK, token, List.of());   // the same songs would be refused again: dropped
                default -> new Answer(Outcome.FAILED, token, List.of());
            };
        } catch (IOException | IllegalArgumentException e) {
            return new Answer(Outcome.FAILED, token, List.of());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Answer(Outcome.FAILED, token, List.of());
        }
    }

    private HttpResponse<String> send(String server, String path, String json, String token) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(stripSlash(server) + path))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("User-Agent", userAgent)
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    static String stripSlash(String server) {
        String plain = server.strip();
        if (!plain.matches("(?i)https?://.*")) {
            plain = "https://" + plain;
        }
        return plain.endsWith("/") ? plain.substring(0, plain.length() - 1) : plain;
    }
}
