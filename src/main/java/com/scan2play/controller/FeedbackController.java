package com.scan2play.controller;

import com.scan2play.entity.FeedbackEntity;
import com.scan2play.repository.FeedbackRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * REST endpoint for collecting DJ feedback (bug reports / feature ideas).
 * Called via AJAX from the dashboard feedback modal — no page reload, no music interruption.
 */
@RestController
@RequestMapping("/dj")
@RequiredArgsConstructor
@Slf4j
public class FeedbackController {

    private final FeedbackRepository feedbackRepository;

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
            OAuth2AuthenticationToken authentication) {

        if (message == null || message.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Message cannot be empty"));
        }
        if (message.length() > 2000) {
            return ResponseEntity.badRequest().body(Map.of("error", "Message too long (max 2000 characters)"));
        }

        String ownerId = authentication != null ? authentication.getName() : "unknown";

        FeedbackEntity feedback = FeedbackEntity.builder()
                .partyCode(partyCode)
                .ownerId(ownerId)
                .message(message.trim())
                .submittedAt(LocalDateTime.now())
                .build();

        feedbackRepository.save(feedback);
        log.info("Feedback saved: partyCode={}, ownerId={}, chars={}", partyCode, ownerId, message.length());

        return ResponseEntity.ok(Map.of("status", "ok"));
    }
}

