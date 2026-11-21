package com.scan2play.deck;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Scan2Play's calls for the program (DeviceApiController): the panel's code swapped for the program's token, the songs that played
 * (answered with the requests marked), and "Odłącz ten komputer". Nothing else is sent.
 */
class ServerClient {

    /** What a call came to; NO_PLAN: the party's organiser has no Pro (any more) — the token stays, the songs are not taken. */
    enum Outcome { OK, WRONG_CODE, DISCONNECTED, TOO_MANY, NO_PLAN, FAILED }

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
            HttpResponse<String> response = send(server, "/api/device/plays", Json.plays(songs, Instant.now()), token);
            return switch (response.statusCode()) {
                case 200 -> new Answer(Outcome.OK, token, Json.strings(response.body(), "marked"));
                case 401 -> new Answer(Outcome.DISCONNECTED, null, List.of());
                case 429 -> new Answer(Outcome.TOO_MANY, token, List.of());
                // the server's own refusal says why; any other 403 (Cloudflare's challenge) is a call that did not get through
                case 403 -> Json.string(response.body(), "error").filter("plan"::equals).isPresent()
                        ? new Answer(Outcome.NO_PLAN, token, List.of()) : new Answer(Outcome.FAILED, token, List.of());
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

    /** "Odłącz ten komputer": the server forgets this computer. True when it is gone there (204, or 401: not known any more). */
    boolean unpair(String server, String token) {
        try {
            int status = send(server, "/api/device/unpair", null, token).statusCode();
            return status == 204 || status == 401;
        } catch (IOException | IllegalArgumentException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private HttpResponse<String> send(String server, String path, String json, String token) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(stripSlash(server) + path))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("User-Agent", userAgent)
                .POST(json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8));
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
