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
    void anyOther403_isACallThatDidNotGetThrough() throws IOException {
        String server = answering(403, "<html>Just a moment...</html>");   // Cloudflare's challenge
        assertEquals(ServerClient.Outcome.FAILED, new ServerClient("test").plays(server, "tok", List.of()).outcome());
    }
}
