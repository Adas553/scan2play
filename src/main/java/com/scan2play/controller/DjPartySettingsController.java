package com.scan2play.controller;

import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.model.PlaybackMode;
import com.scan2play.model.VibeType;
import com.scan2play.service.AccountDeletionService;
import com.scan2play.service.FallbackImportException;
import com.scan2play.service.FallbackPlaylistService;
import com.scan2play.service.PartySettingsCommandService;
import com.scan2play.util.Texts;
import com.scan2play.util.YouTubeUrls;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import static com.scan2play.controller.ViewAttributes.*;

/**
 * Controller responsible for party configuration and lifecycle management.
 * <p>
 * Handles:
 * <ul>
 *     <li>Start / end party</li>
 *     <li>Vibe, rate limits, and playback mode settings</li>
 *     <li>Account deletion (required by Google API Services User Data Policy)</li>
 * </ul>
 * Dashboard views are handled by {@link DjDashboardController}.
 * Song queue actions are handled by {@link DjSongController}.
 */
@Controller
@RequestMapping("/dj")
@RequiredArgsConstructor
@Slf4j
public class DjPartySettingsController {

    /** The most songs the duplicate check looks back at (each is a line of the AI's prompt for every guest's request). */
    static final int MAX_DUPLICATE_CHECK_WINDOW = 50;
    static final int MAX_REQUEST_LIMIT = 100;
    /** A day. */
    static final int MAX_COOLDOWN_MINUTES = 1440;
    /** The length of the {@code fallback_playlist_url} column. */
    static final int FALLBACK_URL_MAX = 500;

    private final PartySettingsCommandService partySettingsCommandService;
    private final AccountDeletionService accountDeletionService;
    private final DjSessionHelper sessionHelper;
    private final FallbackPlaylistService fallbackPlaylistService;

    /**
     * Re-activates the party session.
     */
    @PostMapping("/start-party")
    public String startParty(OAuth2AuthenticationToken authentication, HttpSession session) {
        if (authentication != null) {
            PartySettingsEntity settings = sessionHelper.getPartySettings(authentication, session);
            partySettingsCommandService.updateSettings(settings.getPartyCode(), s -> s.setActive(true));
        }
        return REDIRECT_DASHBOARD;
    }

    /**
     * Ends the current party session without logging out the DJ.
     */
    @PostMapping("/end-party")
    public String endParty(OAuth2AuthenticationToken authentication, HttpSession session) {
        if (authentication != null) {
            log.info("Ending party for DJ: {}", authentication.getName());
            PartySettingsEntity settings = sessionHelper.getPartySettings(authentication, session);
            partySettingsCommandService.updateSettings(settings.getPartyCode(), p -> p.setActive(false));
        }
        return REDIRECT_DASHBOARD;
    }

    /**
     * Updates the global music vibe/theme for the event.
     */
    @PostMapping("/dashboard/vibe")
    public String updateGlobalVibe(@RequestParam String partyCode, @RequestParam VibeType newVibe,
                                   OAuth2AuthenticationToken authentication, HttpSession session) {
        sessionHelper.validateOwnership(partyCode, authentication, session);
        partySettingsCommandService.updateSettings(partyCode, s -> s.setGlobalVibe(newVibe));
        return REDIRECT_DASHBOARD;
    }

    /**
     * The DJ's own words about the vibe (V16): one line, at most {@value PartySettingsEntity#VIBE_NOTE_MAX} characters; empty
     * clears it. The AI is given it with every request, the guests see it on the party page.
     */
    @PostMapping("/dashboard/vibe-note")
    public String updateVibeNote(@RequestParam String partyCode, @RequestParam(required = false) String vibeNote,
                                 OAuth2AuthenticationToken authentication, HttpSession session) {
        sessionHelper.validateOwnership(partyCode, authentication, session);
        String note = Texts.oneLine(vibeNote, PartySettingsEntity.VIBE_NOTE_MAX);
        partySettingsCommandService.updateSettings(partyCode, s -> s.setVibeNote(note.isEmpty() ? null : note));
        return REDIRECT_DASHBOARD;
    }

    /**
     * Who plays (V17), e.g. "DJ Koko": one line, at most {@value PartySettingsEntity#DJ_NAME_MAX} characters; empty clears it. The
     * guests see it on the party page ("🎧 Gra: DJ Koko").
     */
    @PostMapping("/dashboard/dj-name")
    public String updateDjName(@RequestParam String partyCode, @RequestParam(required = false) String djName,
                               OAuth2AuthenticationToken authentication, HttpSession session) {
        sessionHelper.validateOwnership(partyCode, authentication, session);
        String name = Texts.oneLine(djName, PartySettingsEntity.DJ_NAME_MAX);
        partySettingsCommandService.updateSettings(partyCode, s -> s.setDjName(name.isEmpty() ? null : name));
        return REDIRECT_DASHBOARD;
    }

    /**
     * Updates the rate limiting and duplicate checking parameters for the party.
     */
    @PostMapping("/dashboard/limits")
    public String updateLimits(@RequestParam String partyCode,
                               @RequestParam double requestLimit,
                               @RequestParam double cooldownMinutes,
                               @RequestParam int duplicateCheckWindow,
                               OAuth2AuthenticationToken authentication, HttpSession session) {
        sessionHelper.validateOwnership(partyCode, authentication, session);

        // Whole numbers within bounds (review item 5.4): the duplicate window is read from the database and sent to the AI with
        // every guest's request, so it has a ceiling; the guest limit's own ceilings only keep the numbers sensible.
        int safeRequestLimit = clamp((int) Math.round(requestLimit), 1, MAX_REQUEST_LIMIT);
        int safeCooldownMinutes = clamp((int) Math.round(cooldownMinutes), 1, MAX_COOLDOWN_MINUTES);
        int safeDuplicateCheckWindow = clamp(duplicateCheckWindow, 0, MAX_DUPLICATE_CHECK_WINDOW);

        partySettingsCommandService.updateSettings(partyCode, s -> {
            s.setRequestLimit(safeRequestLimit);
            s.setCooldownMinutes(safeCooldownMinutes);
            s.setDuplicateCheckWindow(safeDuplicateCheckWindow);
        });
        return REDIRECT_DASHBOARD;
    }

    /**
     * Sets the playback mode (Auto-Pilot) of the party: to {@code mode} when it is given — what the dashboard's switch sends,
     * the state it shows — or, without it, the other one of the two (a page opened before the switch sent the mode). A toggle alone inverted the setting when the DJ clicked the switch of a window that
     * showed an old state (the setting had been changed on another device).
     */
    @PostMapping("/dashboard/playback-mode")
    public String togglePlaybackMode(@RequestParam String partyCode,
                                     @RequestParam(required = false) PlaybackMode mode,
                                     OAuth2AuthenticationToken authentication, HttpSession session) {
        sessionHelper.validateOwnership(partyCode, authentication, session);
        partySettingsCommandService.updateSettings(partyCode, s -> {
            PlaybackMode newMode = mode != null ? mode
                    : s.getPlaybackMode() == PlaybackMode.AUTO ? PlaybackMode.MANUAL : PlaybackMode.AUTO;
            s.setPlaybackMode(newMode);
        });
        return REDIRECT_DASHBOARD;
    }

    /**
     * Saves or clears the YouTube fallback playlist URL.
     * When the guest queue is empty, Auto-Pilot plays this playlist as background music.
     * <p>
     * Returns 200 OK with the extracted playlist/video ID in the {@code X-Fallback-Id}
     * response header (the dashboard uses it to show or hide the Stop button) — URL parsing
     * logic lives exclusively in {@link YouTubeUrls#extractPlaylistId(String)}.
     * <p>
     * This endpoint is YouTube-only. All YouTube dashboard forms are AJAX-intercepted,
     * so a redirect is not needed (the AJAX handler reads the header instead).
     *
     * @param partyCode          The unique code of the party.
     * @param fallbackPlaylistUrl YouTube playlist URL or ID (blank = clear).
     */
    @PostMapping("/dashboard/fallback-playlist")
    public ResponseEntity<Void> updateFallbackPlaylist(@RequestParam String partyCode,
                                                       @RequestParam(required = false) String fallbackPlaylistUrl,
                                                       OAuth2AuthenticationToken authentication, HttpSession session) {
        sessionHelper.validateOwnership(partyCode, authentication, session);
        String sanitized = (fallbackPlaylistUrl != null && !fallbackPlaylistUrl.isBlank())
                ? fallbackPlaylistUrl.trim()
                : null;
        // A YouTube Mix cannot be read by the Data API: refused before anything is saved, so the party's playlist (and the track
        // that plays from it) carries on, and nothing keeps trying to import it. X-Fallback-Saved: false tells the dashboard.
        // Nor is what is no YouTube link at all ("Hahaha" — before, it was saved and the party's playlist stopped), or a link longer
        // than its column (review item 5.4: it ended in a 500 from the database).
        FallbackImportException.Reason refusal = sanitized != null
                && (sanitized.length() > FALLBACK_URL_MAX || !YouTubeUrls.looksLikePlaylistOrVideo(sanitized))
                ? FallbackImportException.Reason.NOT_A_LINK
                : YouTubeUrls.isMix(YouTubeUrls.extractPlaylistId(sanitized)) ? FallbackImportException.Reason.YOUTUBE_MIX : null;
        if (refusal != null) {
            log.info("Party [{}]: fallback playlist not saved ({}): {}", partyCode, refusal,
                    sanitized.substring(0, Math.min(sanitized.length(), 120)));
            return ResponseEntity.ok()
                    .header("X-Fallback-Saved", "false")
                    .header("X-Fallback-Import", "failed")
                    .header("X-Fallback-Import-Reason", refusal.name())
                    .build();
        }
        PartySettingsEntity saved = partySettingsCommandService.updateSettings(partyCode,
                s -> s.setFallbackPlaylistUrl(sanitized));

        String extractedId = YouTubeUrls.extractPlaylistId(sanitized);
        log.info("Party [{}]: Fallback playlist updated to: {} (extracted: {})",
                partyCode, sanitized != null ? sanitized : "(cleared)", extractedId);

        ResponseEntity.BodyBuilder response = ResponseEntity.ok()
                .header("X-Fallback-Id", extractedId != null ? extractedId : "");

        // Server-side copy of the playlist (Section 14, Phase 2) — Auto-Pilot plays from it via NextTrackService.
        // Best-effort here: a failed import must not fail saving the setting; NextTrackService imports lazily
        // when it has nothing to play, and the outcome is reported in the X-Fallback-Import headers.
        try {
            int tracks = fallbackPlaylistService.syncFallbackTracks(partyCode, extractedId, saved.isFallbackShuffle());
            response.header("X-Fallback-Import", "ok")
                    .header("X-Fallback-Tracks", String.valueOf(tracks));
        } catch (FallbackImportException e) {
            log.warn("Party [{}]: fallback playlist import failed ({}): {}", partyCode, e.getReason(), e.getMessage());
            response.header("X-Fallback-Import", "failed")
                    .header("X-Fallback-Import-Reason", e.getReason().name());
        }
        return response.build();
    }

    /**
     * Toggles shuffle mode for the fallback playlist and re-orders the tracks that are still to play: a fresh
     * random order when shuffle is switched on, playlist order (continuing after the last track played) when it
     * is switched off — so the "up next" list changes at once and what it shows is what will play.
     * Returns 200 OK with the new shuffle state in the {@code X-Fallback-Shuffle} header.
     */
    @PostMapping("/dashboard/fallback-shuffle")
    public ResponseEntity<Void> toggleFallbackShuffle(@RequestParam String partyCode,
                                                      OAuth2AuthenticationToken authentication, HttpSession session) {
        sessionHelper.validateOwnership(partyCode, authentication, session);
        PartySettingsEntity updated = partySettingsCommandService.updateSettings(partyCode,
                s -> s.setFallbackShuffle(!s.isFallbackShuffle()));

        log.info("Party [{}]: Fallback shuffle toggled to {}", partyCode, updated.isFallbackShuffle());

        String playlistId = YouTubeUrls.extractPlaylistId(updated.getFallbackPlaylistUrl());
        if (playlistId != null) {
            fallbackPlaylistService.applyShuffleSetting(partyCode, playlistId, updated.isFallbackShuffle());
        }

        return ResponseEntity.ok()
                .header("X-Fallback-Shuffle", String.valueOf(updated.isFallbackShuffle()))
                .build();
    }

    /**
     * Deletes all data associated with the currently logged-in DJ account.
     * Required by Google API Services User Data Policy — users must be able to delete their data.
     * After deletion, the session is invalidated and the user is redirected to the home page.
     */
    @PostMapping("/delete-account")
    public String deleteAccount(OAuth2AuthenticationToken authentication, HttpSession session) {
        if (authentication != null) {
            String ownerId = authentication.getName();
            log.info("Account deletion requested by ownerId={}", ownerId);
            accountDeletionService.deleteAllUserData(ownerId);
            session.invalidate();
        }
        return REDIRECT_HOME;
    }

    private static int clamp(int value, int min, int max) {
        return Math.min(max, Math.max(min, value));
    }
}

