package com.scan2play.controller;

import com.scan2play.model.PlayerLeaseMode;
import com.scan2play.model.PlayerLeaseResponse;
import com.scan2play.service.PartySettingsQueryService;
import com.scan2play.service.PlayerLeaseService;
import com.scan2play.util.YouTubeUrls;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * Which dashboard window plays the party's music — see {@link PlayerLeaseService}. Only YouTube Auto-Pilot
 * ({@code youtube-autopilot.js}) calls these endpoints.
 */
@Controller
@RequestMapping("/dj")
@RequiredArgsConstructor
public class DjPlayerLeaseController {

    private final PlayerLeaseService playerLeaseService;
    private final DjSessionHelper sessionHelper;
    private final PartySettingsQueryService partySettingsQueryService;

    /**
     * A dashboard window reports in (every few seconds while it is open) and learns whether it is the one that
     * plays. It is not read-only: the report renews the lease, and {@code TAKE_OVER} moves it.
     * <p>
     * The answer also names the party's current fallback playlist: the DJ can replace or clear it in a window that
     * does not play, and the window that does must then stop the background track it is playing (its track comes
     * from the old playlist) — that window compares this id with the {@code playlistId} of the track it was given.
     * <p>
     * 400 Bad Request when the window id is not a well-formed random id.
     */
    @PostMapping("/dashboard/player-lease")
    @ResponseBody
    public ResponseEntity<PlayerLeaseResponse> report(@RequestParam String partyCode,
                                                      @RequestParam String deviceId,
                                                      @RequestParam PlayerLeaseMode mode,
                                                      OAuth2AuthenticationToken authentication,
                                                      HttpSession session) {
        sessionHelper.validateOwnership(partyCode, authentication, session);
        if (!PlayerLeaseService.isValidDeviceId(deviceId)) {
            return ResponseEntity.badRequest().build();
        }
        PlayerLeaseService.Status status = playerLeaseService.report(partyCode, deviceId, mode);
        String playlistId = YouTubeUrls.extractPlaylistId(
                partySettingsQueryService.getSettings(partyCode).getFallbackPlaylistUrl());
        return ResponseEntity.ok(new PlayerLeaseResponse(status.holder(), status.free(), playlistId));
    }

    /**
     * A window that holds the lease is going away (tab closed, page left) — sent with {@code sendBeacon}, so there
     * is no answer to wait for. Ignored when the window does not hold the lease.
     * <p>
     * 204 No Content; 400 Bad Request when the window id is not a well-formed random id.
     */
    @PostMapping("/dashboard/player-lease/release")
    public ResponseEntity<Void> release(@RequestParam String partyCode,
                                        @RequestParam String deviceId,
                                        OAuth2AuthenticationToken authentication,
                                        HttpSession session) {
        sessionHelper.validateOwnership(partyCode, authentication, session);
        if (!PlayerLeaseService.isValidDeviceId(deviceId)) {
            return ResponseEntity.badRequest().build();
        }
        playerLeaseService.release(partyCode, deviceId);
        return ResponseEntity.noContent().build();
    }
}
