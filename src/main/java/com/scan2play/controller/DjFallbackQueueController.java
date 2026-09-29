package com.scan2play.controller;

import com.scan2play.model.FallbackQueueView;
import com.scan2play.model.MoveDirection;
import com.scan2play.service.FallbackQueueService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * The DJ's view of the fallback (background music) playlist queue, and the moves the DJ can make in it.
 */
@Controller
@RequestMapping("/dj")
@RequiredArgsConstructor
public class DjFallbackQueueController {

    static final String QUEUE_ATTRIBUTE = "queue";
    static final String VERSION_HEADER = "X-Queue-Version";

    private final FallbackQueueService fallbackQueueService;
    private final DjSessionHelper sessionHelper;

    /**
     * The "up next" list as an HTML fragment, fetched by the dashboard whenever it may have changed: on page load,
     * after the DJ saves or clears the playlist or toggles shuffle, and when the player takes the next
     * background track. Read-only.
     * <p>
     * The response header {@value #VERSION_HEADER} is the version of the list (the same value the dashboard's lease
     * reports carry): a window that has just fetched the list knows it is up to date, and asks again only when the
     * version changes.
     */
    @GetMapping("/dashboard/fallback-queue")
    public String fallbackQueue(@RequestParam String partyCode,
                                Model model,
                                HttpServletResponse response,
                                OAuth2AuthenticationToken authentication,
                                HttpSession session) {
        sessionHelper.validateOwnership(partyCode, authentication, session);
        FallbackQueueView queue = fallbackQueueService.getUpcoming(partyCode);
        response.setHeader(VERSION_HEADER, FallbackQueueService.versionOf(queue));
        model.addAttribute(QUEUE_ATTRIBUTE, queue);
        return "fragments/fallback-queue :: queue";
    }

    /**
     * Moves one track of the queue: {@code UP} / {@code DOWN} one place, or {@code TOP} to play it next. The
     * dashboard refreshes the list afterwards.
     * <p>
     * 204 No Content when done; 409 Conflict when the track can no longer be moved (the player has just taken it,
     * or it is not part of this party's current playlist).
     */
    @PostMapping("/dashboard/fallback-queue/move")
    public ResponseEntity<Void> moveTrack(@RequestParam String partyCode,
                                          @RequestParam Long trackId,
                                          @RequestParam MoveDirection direction,
                                          OAuth2AuthenticationToken authentication,
                                          HttpSession session) {
        sessionHelper.validateOwnership(partyCode, authentication, session);
        return fallbackQueueService.moveTrack(partyCode, trackId, direction)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.status(409).build();
    }

    /**
     * The DJ drags a track to a new place: right in front of the track {@code beforeTrackId}, or to the end of the queue
     * when that is left out. The dashboard refreshes the list afterwards.
     * <p>
     * 204 No Content when done; 409 Conflict when one of the tracks can no longer be moved (the player has just taken
     * it, or it is not part of this party's current playlist).
     */
    @PostMapping("/dashboard/fallback-queue/place")
    public ResponseEntity<Void> placeTrack(@RequestParam String partyCode,
                                           @RequestParam Long trackId,
                                           @RequestParam(required = false) Long beforeTrackId,
                                           OAuth2AuthenticationToken authentication,
                                           HttpSession session) {
        sessionHelper.validateOwnership(partyCode, authentication, session);
        return fallbackQueueService.placeTrack(partyCode, trackId, beforeTrackId)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.status(409).build();
    }
}
