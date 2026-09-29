package com.scan2play.controller;

import com.scan2play.model.PlayerCommand;
import com.scan2play.model.PlayerLeaseMode;
import com.scan2play.model.PlayerLeaseResponse;
import com.scan2play.service.FallbackQueueService;
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
 * Which dashboard window plays the party's music, and the DJ's commands to it — see {@link PlayerLeaseService}.
 * Only YouTube Auto-Pilot ({@code youtube-autopilot.js}) calls these endpoints.
 */
@Controller
@RequestMapping("/dj")
@RequiredArgsConstructor
public class DjPlayerLeaseController {

    private final PlayerLeaseService playerLeaseService;
    private final DjSessionHelper sessionHelper;
    private final PartySettingsQueryService partySettingsQueryService;
    private final FallbackQueueService fallbackQueueService;

    /**
     * A dashboard window reports in (every few seconds while it is open) and learns whether it is the one that
     * plays. It is not read-only: the report renews the lease, {@code TAKE_OVER} moves it, and the window that
     * holds it collects the command the DJ gave it from another window (handed out once).
     * <p>
     * The answer also names the party's current fallback playlist: the DJ can replace or clear it in a window that
     * does not play, and the window that does must then stop the background track it is playing (its track comes
     * from the old playlist) — that window compares this id with the {@code playlistId} of the track it was given.
     * And it carries a version of the "up next" list, so that every window can tell when the list changed elsewhere.
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
        return ResponseEntity.ok(new PlayerLeaseResponse(status.holder(), status.free(), playlistId,
                fallbackQueueService.getVersion(partyCode), status.command()));
    }

    /**
     * The DJ gives the window that plays a command from any window of the party (the phone as a remote control):
     * it is picked up by the window that plays with its next report, i.e. within a few seconds.
     * <p>
     * 204 No Content when the command is waiting for the window that plays; 409 Conflict when no window plays (the
     * lease is free, so nobody would ever carry it out); 400 Bad Request for an unknown command.
     */
    @PostMapping("/dashboard/player-command")
    public ResponseEntity<Void> sendCommand(@RequestParam String partyCode,
                                            @RequestParam PlayerCommand command,
                                            OAuth2AuthenticationToken authentication,
                                            HttpSession session) {
        sessionHelper.validateOwnership(partyCode, authentication, session);
        return playerLeaseService.sendCommand(partyCode, command)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.status(409).build();
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
