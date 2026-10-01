package com.scan2play.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Where browsers report what the Content-Security-Policy would block ({@code report-uri}, see {@code SecurityConfig}; review item
 * 5.1). While the policy is only reported, these lines in the log show what still has to change before it is enforced.
 * <p>
 * Public, without CSRF (a browser sends the report on its own) — so anyone can post here: the body is read up to
 * {@value #MAX_BODY} bytes, each distinct violation is logged once per {@link #REPEAT} (a page with a blocked script reports it on
 * every load), the logged fields are cut short, and the address of the page loses its query.
 */
@RestController
@Slf4j
public class CspReportController {

    static final int MAX_BODY = 8 * 1024;
    private static final Duration REPEAT = Duration.ofHours(1);
    private static final int MAX_FIELD = 200;

    /** Only reads a tree: no configuration of the app's mapper is needed. */
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Cache<String, Boolean> logged = Caffeine.newBuilder().expireAfterWrite(REPEAT).maximumSize(1_000).build();

    @PostMapping("/csp-report")
    public ResponseEntity<Void> report(HttpServletRequest request) {
        try {
            String body = new String(request.getInputStream().readNBytes(MAX_BODY), StandardCharsets.UTF_8);
            JsonNode report = objectMapper.readTree(body).path("csp-report");
            if (report.isObject()) {
                String directive = field(report, "effective-directive", field(report, "violated-directive", "?"));
                String blocked = field(report, "blocked-uri", "?");
                String page = withoutQuery(field(report, "document-uri", "?"));
                String source = field(report, "source-file", "") + ":" + report.path("line-number").asText("");
                if (logged.asMap().putIfAbsent(directive + ' ' + blocked + ' ' + page, Boolean.TRUE) == null) {
                    log.warn("CSP violation: {} blocked '{}' on {} (from {})", directive, blocked, page, source);
                }
            }
        } catch (IOException | RuntimeException e) {
            log.debug("Unreadable CSP report: {}", e.getMessage());
        }
        return ResponseEntity.noContent().build();
    }

    private static String field(JsonNode report, String name, String otherwise) {
        String value = report.path(name).asText("");
        if (value.isBlank()) {
            return otherwise;
        }
        value = value.replaceAll("[\\r\\n\\t]", " ");
        return value.length() > MAX_FIELD ? value.substring(0, MAX_FIELD) + "…" : value;
    }

    private static String withoutQuery(String url) {
        int query = url.indexOf('?');
        return query < 0 ? url : url.substring(0, query);
    }
}
