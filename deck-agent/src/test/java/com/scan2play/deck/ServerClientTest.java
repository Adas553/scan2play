package com.scan2play.deck;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The program's reading of the server's answers to its songs, on a little HTTP server of the test's own. */
class ServerClientTest {

    private HttpServer http;

    private String answering(int status, String body) throws IOException {
        http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        http.createContext("/api/device/plays", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        http.start();
        return "http://127.0.0.1:" + http.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        if (http != null) {
            http.stop(0);
        }
    }

    @Test
    void the403OfAPartyWithoutPro_isNoPlan() throws IOException {
        String server = answering(403, "{\"error\":\"plan\"}");
        assertEquals(ServerClient.Outcome.NO_PLAN, new ServerClient("test").plays(server, "tok", List.of()).outcome());
    }

    @Test
    void unpair_sendsTheToken_andIsDoneOn204Or401() throws IOException {
        String[] heard = new String[1];
        http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        http.createContext("/api/device/unpair", exchange -> {
            heard[0] = exchange.getRequestMethod() + " " + exchange.getRequestHeaders().getFirst("Authorization");
            exchange.sendResponseHeaders("Bearer gone".equals(exchange.getRequestHeaders().getFirst("Authorization")) ? 401 : 204, -1);
            exchange.close();
        });
        http.start();
        String server = "http://127.0.0.1:" + http.getAddress().getPort();

        assertEquals(true, new ServerClient("test").unpair(server, "tok"));
        assertEquals("POST Bearer tok", heard[0]);
        assertEquals(true, new ServerClient("test").unpair(server, "gone"), "not known there: as good as removed");
        assertEquals(false, new ServerClient("test").unpair("http://127.0.0.1:1", "tok"), "no server");
    }

    @Test
    void anyOther403_isACallThatDidNotGetThrough() throws IOException {
        String server = answering(403, "<html>Just a moment...</html>");   // Cloudflare's challenge
        assertEquals(ServerClient.Outcome.FAILED, new ServerClient("test").plays(server, "tok", List.of()).outcome());
    }

    /**
     * The server takes at most 500 characters an artist or a title and refuses the whole call otherwise (400), which the program
     * drops: one broken tag would lose the other songs of its call. Longer is cut here, the rest of the call goes through.
     */
    @Test
    void aFieldLongerThanTheServerTakes_isCut_notTheWholeCallLost() {
        String json = Json.plays(List.of(new Played(null, "A".repeat(700), "T".repeat(501))), java.time.Instant.EPOCH);
        assertEquals(true, json.contains("\"" + "A".repeat(Json.FIELD_MAX) + "\""), json.length() + " characters");
        assertEquals(false, json.contains("A".repeat(Json.FIELD_MAX + 1)));
        assertEquals(false, json.contains("T".repeat(Json.FIELD_MAX + 1)));
    }
}
