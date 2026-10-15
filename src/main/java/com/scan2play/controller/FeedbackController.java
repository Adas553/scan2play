package com.scan2play.controller;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.scan2play.entity.FeedbackEntity;
import com.scan2play.repository.FeedbackRepository;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * REST endpoint for collecting DJ feedback (bug reports / feature ideas).
 * Called via AJAX from the dashboard feedback modal — no page reload, no music interruption.
 */
@RestController
@RequestMapping("/dj")
@RequiredArgsConstructor
@Slf4j
public class FeedbackController {

    /**
     * How many messages one account may send an hour (review 2026-10-14): a person writes one or two; a script would fill the table.
     * Counted in this instance's memory, by the account — as the guests' limits (one instance, PROJECT_CONTEXT 12).
     */
    static final int MAX_PER_HOUR = 10;

    private final Cache<String, AtomicInteger> sentThisHour = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofHours(1)).maximumSize(10_000).build();

    private final FeedbackRepository feedbackRepository;
    private final DjSessionHelper sessionHelper;

    /**
     * Accepts a feedback submission from a DJ and persists it.
     *
     * @param partyCode      The unique code of the party (identifies the DJ session).
     * @param message        The feedback message body (max 2000 characters).
     * @param authentication The current DJ's OAuth2 authentication token.
     * @return {@code {"status":"ok"}} on success, {@code {"error":"..."}} on validation failure.
     */
    @PostMapping("/feedback")
    public ResponseEntity<Map<String, String>> submitFeedback(
            @RequestParam String partyCode,
            @RequestParam String message,
            OAuth2AuthenticationToken authentication,
            HttpSession session) {

        sessionHelper.access(partyCode, authentication, session);   // the owner or the staff (V30)

        if (message == null || message.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Message cannot be empty"));
        }
        if (message.length() > 2000) {
            return ResponseEntity.badRequest().body(Map.of("error", "Message too long (max 2000 characters)"));
        }

        String ownerId = authentication.getName();   // /dj/** needs the login; validateAccess has used it already
        if (sentThisHour.get(ownerId, id -> new AtomicInteger()).incrementAndGet() > MAX_PER_HOUR) {
            log.warn("Feedback refused: more than {} an hour from ownerId={}", MAX_PER_HOUR, ownerId);
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(Map.of("error", "Too many messages — try again later"));
        }

        FeedbackEntity feedback = FeedbackEntity.builder()
                .partyCode(partyCode)
                .ownerId(ownerId)
                .message(message.trim())
                .submittedAt(Instant.now())
                .build();

        feedbackRepository.save(feedback);
        log.info("Feedback saved: partyCode={}, ownerId={}, chars={}", partyCode, ownerId, message.length());

        return ResponseEntity.ok(Map.of("status", "ok"));
    }
}

