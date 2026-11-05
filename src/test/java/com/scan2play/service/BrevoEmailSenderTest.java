package com.scan2play.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scan2play.service.EmailSender.Email;
import com.scan2play.service.EmailSender.Result;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Brevo's API as the sender calls it (a local server in its place): the key in its header, the sender, the reply-to and the one-click
 * "Unsubscribe" in the body; 201 sent, 401 / 403 the key refused, anything else a failure to try again — never an exception; no key,
 * no call.
 */
class BrevoEmailSenderTest {

    private HttpServer server;
    private final AtomicInteger status = new AtomicInteger(201);
    private final List<String> bodies = new ArrayList<>();
    private final List<String> keys = new ArrayList<>();

    private BrevoEmailSender sender(String key) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v3/smtp/email", exchange -> {
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            keys.add(exchange.getRequestHeaders().getFirst("api-key"));
            byte[] answer = (status.get() == 201 ? "{\"messageId\":\"<1@smtp-relay.mailin.fr>\"}"
                    : "{\"code\":\"unauthorized\",\"message\":\"Key not found\"}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status.get(), answer.length);
            exchange.getResponseBody().write(answer);
            exchange.close();
        });
        server.start();
        return new BrevoEmailSender(HttpClient.newHttpClient(), "http://127.0.0.1:" + server.getAddress().getPort() + "/v3/smtp/email",
                key, "kontakt@scan2play.com.pl", "Scan2Play", "scan2playcontact@gmail.com");
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private static Email email() {
        return new Email("dj@example.com", "Scan2Play — dzięki", "<p>Cześć</p>", "Cześć\n", "https://www.scan2play.com.pl/email/stop/abc?lang=pl");
    }

    @Test
    void aSend_carriesTheKey_theSender_theReplyTo_andTheOneClickUnsubscribe() throws Exception {
        assertThat(sender(" secret-key ").send(email())).isEqualTo(Result.SENT);

        assertThat(keys).containsExactly("secret-key");
        JsonNode body = new ObjectMapper().readTree(bodies.getFirst());
        assertThat(body.at("/sender/email").asText()).isEqualTo("kontakt@scan2play.com.pl");
        assertThat(body.at("/sender/name").asText()).isEqualTo("Scan2Play");
        assertThat(body.at("/replyTo/email").asText()).isEqualTo("scan2playcontact@gmail.com");
        assertThat(body.at("/to/0/email").asText()).isEqualTo("dj@example.com");
        assertThat(body.at("/subject").asText()).isEqualTo("Scan2Play — dzięki");
        assertThat(body.at("/htmlContent").asText()).isEqualTo("<p>Cześć</p>");
        assertThat(body.at("/textContent").asText()).isEqualTo("Cześć\n");
        assertThat(body.at("/headers/List-Unsubscribe").asText()).isEqualTo("<https://www.scan2play.com.pl/email/stop/abc?lang=pl>");
        assertThat(body.at("/headers/List-Unsubscribe-Post").asText()).isEqualTo("List-Unsubscribe=One-Click");
    }

    @Test
    void aRefusedKey_isSaidSo_otherFailuresAreFailures() throws Exception {
        BrevoEmailSender sender = sender("expired");
        status.set(401);
        assertThat(sender.send(email())).isEqualTo(Result.KEY_REFUSED);
        status.set(403);
        assertThat(sender.send(email())).isEqualTo(Result.KEY_REFUSED);
        status.set(400);
        assertThat(sender.send(email())).isEqualTo(Result.FAILED);
        status.set(503);
        assertThat(sender.send(email())).isEqualTo(Result.FAILED);
    }

    @Test
    void noAnswer_isAFailure_notAnException() throws Exception {
        BrevoEmailSender sender = sender("key");
        server.stop(0);
        server = null;
        assertThat(sender.send(email())).isEqualTo(Result.FAILED);
    }

    @Test
    void withoutAKey_nothingIsCalled() throws Exception {
        BrevoEmailSender sender = sender("  ");
        assertThat(sender.isOn()).isFalse();
        assertThat(sender.send(email())).isEqualTo(Result.OFF);
        assertThat(bodies).isEmpty();
    }
}
