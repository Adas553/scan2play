package com.scan2play.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * The e-mails through Brevo's transactional API (v3, {@code POST /v3/smtp/email}): from {@code Scan2Play <contact@scan2play.com.pl>}
 * — the domain is authenticated in Brevo (DKIM, DMARC) — with the replies to the Gmail address the owner reads (Brevo refuses a free
 * address as the sender, and Gmail's DMARC would mark it forged). The key is the Railway variable {@code BREVO_API_KEY}; without it
 * nothing is sent ({@link Result#OFF}). A refused key (401 / 403) is logged as an error that says what to do: Brevo expires a key
 * unused for 90 days, and every e-mail fails until it is changed.
 */
@Component
@Slf4j
public class BrevoEmailSender implements EmailSender {

    static final String DEFAULT_ENDPOINT = "https://api.brevo.com/v3/smtp/email";
    /** How long Brevo has to answer a sent e-mail; after it the e-mail is {@link Result#UNKNOWN}. */
    static final Duration ANSWER_TIMEOUT = Duration.ofSeconds(15);

    private final HttpClient httpClient;
    private final ObjectMapper json = new ObjectMapper();
    private final String endpoint;
    private final String apiKey;
    private final String fromAddress;
    private final String fromName;
    private final String replyTo;
    private final Duration answerTimeout;

    @Autowired
    public BrevoEmailSender(@Value("${brevo.api-key:}") String apiKey,
                            @Value("${scan2play.mail.from:contact@scan2play.com.pl}") String fromAddress,
                            @Value("${scan2play.mail.from-name:Scan2Play}") String fromName,
                            @Value("${scan2play.mail.reply-to:scan2playcontact@gmail.com}") String replyTo) {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(), DEFAULT_ENDPOINT, apiKey, fromAddress, fromName,
                replyTo, ANSWER_TIMEOUT);
    }

    /** For the tests: their own endpoint and how long it may take. */
    BrevoEmailSender(HttpClient httpClient, String endpoint, String apiKey, String fromAddress, String fromName, String replyTo,
                     Duration answerTimeout) {
        this.httpClient = httpClient;
        this.endpoint = endpoint;
        this.apiKey = apiKey == null ? "" : apiKey.strip();
        this.fromAddress = fromAddress;
        this.fromName = fromName;
        this.replyTo = replyTo;
        this.answerTimeout = answerTimeout;
        if (this.apiKey.isEmpty()) {
            log.warn("E-mails off: BREVO_API_KEY is not set — no welcome or morning summary is sent from this server");
        } else {
            log.info("E-mails on (Brevo), from {}", fromAddress);
        }
    }

    public boolean isOn() {
        return !apiKey.isEmpty();
    }

    @Override
    public Result send(Email email) {
        if (!isOn()) {
            return Result.OFF;
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(answerTimeout)
                    .header("api-key", apiKey)
                    .header("accept", "application/json")
                    .header("content-type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body(email), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status >= 200 && status < 300) {
                return Result.SENT;
            }
            if (status == 401 || status == 403) {
                log.error("E-MAIL KEY REFUSED: Brevo answered HTTP {} — BREVO_API_KEY is wrong, revoked, or expired (Brevo expires a key"
                        + " unused for 90 days). No e-mail goes until a new key (Restricted, Transactional email = All) is set on"
                        + " Railway. Brevo said: {}", status, shortly(response.body()));
                return Result.KEY_REFUSED;
            }
            log.warn("E-mail not sent: Brevo answered HTTP {}: {}", status, shortly(response.body()));
            return Result.FAILED;
        } catch (HttpConnectTimeoutException e) {
            log.warn("E-mail not sent: Brevo could not be reached ({})", e.toString());
            return Result.FAILED;
        } catch (HttpTimeoutException e) {
            // the request went out: Brevo may have taken it — not sent again
            log.warn("E-mail maybe sent: Brevo did not answer in {} s", answerTimeout.toSeconds());
            return Result.UNKNOWN;
        } catch (IOException e) {
            log.warn("E-mail not sent: Brevo did not answer ({})", e.toString());
            return Result.FAILED;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.FAILED;
        } catch (RuntimeException e) {
            log.warn("E-mail not sent: {}", e.toString());
            return Result.FAILED;
        }
    }

    String body(Email email) throws IOException {
        ObjectNode root = json.createObjectNode();
        root.putObject("sender").put("name", fromName).put("email", fromAddress);
        root.putArray("to").addObject().put("email", email.to());
        root.putObject("replyTo").put("email", replyTo);
        root.put("subject", email.subject());
        root.put("htmlContent", email.html());
        if (email.text() != null) {
            root.put("textContent", email.text());
        }
        if (email.stopUrl() != null) {
            root.putObject("headers")
                    .put("List-Unsubscribe", "<" + email.stopUrl() + ">")
                    .put("List-Unsubscribe-Post", "List-Unsubscribe=One-Click");
        }
        return json.writeValueAsString(root);
    }

    /** Brevo's answer for the log: its error code and message — never the address (Brevo's errors do not repeat it). */
    private static String shortly(String body) {
        if (body == null) {
            return "";
        }
        String line = body.replaceAll("\\s+", " ").strip();
        return line.length() > 200 ? line.substring(0, 200) + "…" : line;
    }
}
