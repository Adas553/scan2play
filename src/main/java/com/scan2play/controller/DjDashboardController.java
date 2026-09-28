package com.scan2play.controller;

import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.model.NextGuestTrackResponse;
import com.scan2play.service.DjService;
import com.scan2play.service.PartySettingsQueryService;
import com.scan2play.service.QrCodeService;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import static com.scan2play.controller.ViewAttributes.*;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Controller responsible for DJ dashboard views and AJAX polling endpoints.
 * <p>
 * Handles:
 * <ul>
 *     <li>Main dashboard view</li>
 *     <li>Dashboard AJAX updates (ETag-based polling)</li>
 *     <li>History view and history fragment</li>
 * </ul>
 * Party settings changes are handled by {@link DjPartySettingsController}.
 * Song queue actions are handled by {@link DjSongController}.
 */
@Controller
@RequestMapping("/dj")
@RequiredArgsConstructor
@Slf4j
public class DjDashboardController {

    /** Extracts YouTube playlist ID from a full URL (e.g. ?list=PLxxxxxx). */
    private static final Pattern PLAYLIST_ID_PATTERN = Pattern.compile("[?&]list=([A-Za-z0-9_-]+)");

    /** Extracts YouTube video ID from watch URLs (e.g. ?v=xxxxx). */
    private static final Pattern VIDEO_ID_V_PATTERN = Pattern.compile("[?&]v=([A-Za-z0-9_-]{11})");

    /** Extracts YouTube video ID from short URLs (e.g. youtu.be/xxxxx). */
    private static final Pattern VIDEO_ID_SHORT_PATTERN = Pattern.compile("youtu\\.be/([A-Za-z0-9_-]{11})");

    private final DjService djService;
    private final PartySettingsQueryService partySettingsQueryService;
    private final QrCodeService qrCodeService;
    private final DjSessionHelper sessionHelper;

    @Value("${scan2play.guest-url}")
    private String rawBaseUrl;

    private String cleanBaseUrl;

    /**
     * Sanitizes the guest URL once at startup to avoid repeated string operations during requests.
     */
    @PostConstruct
    public void init() {
        this.cleanBaseUrl = rawBaseUrl.endsWith("/")
                ? rawBaseUrl.substring(0, rawBaseUrl.length() - 1)
                : rawBaseUrl;
    }

    /**
     * Displays the DJ/Admin control panel (Active Queue).
     */
    @GetMapping("/dashboard")
    public String dashboard(Model model, OAuth2AuthenticationToken authentication, HttpSession session) {
        if (authentication == null) {
            return REDIRECT_LOGIN;
        }

        PartySettingsEntity settings = sessionHelper.getPartySettings(authentication, session);
        String partyCode = settings.getPartyCode();

        log.info("DJ Dashboard access: ownerId={}, partyCode={}", authentication.getName(), partyCode);

        model.addAttribute(PARTY_CODE, partyCode);
        model.addAttribute(IS_ACTIVE, settings.isActive());

        // --- Party State ---
        model.addAttribute(GLOBAL_VIBE, settings.getGlobalVibe());
        model.addAttribute(ACTIVE_PROVIDER, settings.getActiveProvider());
        model.addAttribute(PLAYBACK_MODE, settings.getPlaybackMode());
        model.addAttribute(IS_SPOTIFY_CONNECTED, settings.getSpotifyAccessToken() != null);
        model.addAttribute(REQUEST_LIMIT, settings.getRequestLimit());
        model.addAttribute(COOLDOWN_MINUTES, settings.getCooldownMinutes());
        model.addAttribute(DUPLICATE_CHECK_WINDOW, settings.getDuplicateCheckWindow());
        model.addAttribute(FALLBACK_PLAYLIST_ID, extractPlaylistId(settings.getFallbackPlaylistUrl()));
        model.addAttribute(FALLBACK_PLAYLIST_URL, settings.getFallbackPlaylistUrl());
        model.addAttribute(FALLBACK_SHUFFLE, settings.isFallbackShuffle());

        // --- QR Code ---
        String guestUrl = cleanBaseUrl + "/p/" + partyCode;
        String qrCodeBase64Str = qrCodeService.generateQrCodeBase64(guestUrl, 250, 250);
        model.addAttribute(QR_CODE_BASE64, qrCodeBase64Str);
        model.addAttribute(PERMANENT_LINK, guestUrl);

        // --- Active Queue (Accepted songs only) ---
        model.addAttribute(HISTORY, djService.getDashboardQueue(partyCode));

        return "dashboard";
    }

    /**
     * Displays the history of played and rejected songs.
     */
    @GetMapping("/history-view")
    public String historyView(Model model, OAuth2AuthenticationToken authentication, HttpSession session) {
        if (authentication == null) {
            return REDIRECT_LOGIN;
        }
        PartySettingsEntity settings = sessionHelper.getPartySettings(authentication, session);
        String partyCode = settings.getPartyCode();

        model.addAttribute(PARTY_CODE, partyCode);
        model.addAttribute(IS_ACTIVE, settings.isActive());
        model.addAttribute(HISTORY, djService.getHistory(partyCode));

        return "history";
    }

    /**
     * Returns the history table as an HTML fragment for AJAX-based tab switching.
     * Used by the dashboard to load history without a full page reload,
     * which preserves the YouTube IFrame player state.
     */
    @GetMapping("/history-view/fragment")
    public String historyFragment(@RequestParam String partyCode, Model model,
                                  OAuth2AuthenticationToken authentication, HttpSession session) {
        sessionHelper.validateOwnership(partyCode, authentication, session);
        model.addAttribute(HISTORY, djService.getHistory(partyCode));
        return "history :: historyTableContent";
    }

    /**
     * AJAX polling endpoint that returns a partial HTML fragment of the song request table.
     * <p>
     * Supports ETag-based conditional responses: if the queue hasn't changed since the
     * client's last poll, returns 304 Not Modified (empty body). This avoids unnecessary
     * Thymeleaf rendering, reduces bandwidth, and prevents the client-side DOM replacement
     * that would reset any active column sorting.
     */
    @GetMapping("/dashboard/updates")
    public String getDashboardUpdates(@RequestParam String partyCode, Model model,
                                      HttpServletRequest request, HttpServletResponse response,
                                      OAuth2AuthenticationToken authentication, HttpSession session) {
        sessionHelper.validateOwnership(partyCode, authentication, session);
        // --- Lightweight fingerprint check (avoids full query + render) ---
        String fingerprint = djService.getQueueFingerprint(partyCode);
        String etag = "\"q-" + fingerprint + "\"";

        String ifNoneMatch = request.getHeader("If-None-Match");
        if (etag.equals(ifNoneMatch)) {
            response.setStatus(HttpServletResponse.SC_NOT_MODIFIED);
            response.setHeader("ETag", etag);
            return null;  // response is short-circuited — Spring skips view resolution
        }

        // --- Full render (queue changed) ---
        response.setHeader("ETag", etag);
        PartySettingsEntity settings = partySettingsQueryService.getSettings(partyCode);
        model.addAttribute(ACTIVE_PROVIDER, settings.getActiveProvider());
        model.addAttribute(PLAYBACK_MODE, settings.getPlaybackMode());
        model.addAttribute(IS_SPOTIFY_CONNECTED, settings.getSpotifyAccessToken() != null);
        model.addAttribute(HISTORY, djService.getDashboardQueue(partyCode));
        return "dashboard :: songTableBody";
    }

    /**
     * Server-side "what's next" decision for the YouTube Auto-Pilot client.
     * <p>
     * Returns the oldest accepted guest song with a playable video ID, or 204 No Content
     * when none is ready (the client then falls back to the background playlist). This
     * replaces the old client-side DOM scan of the queue table — see PROJECT_CONTEXT.md
     * Section 14. Read-only: the client still confirms playback via the existing
     * {@code POST /dj/dashboard/play} once the video actually starts.
     *
     * @param exclude Optional comma-separated song IDs the client already knows are
     *                broken (the YouTube player itself errored on them) and wants skipped.
     */
    @GetMapping("/dashboard/next-guest-track")
    @ResponseBody
    public ResponseEntity<NextGuestTrackResponse> nextGuestTrack(@RequestParam String partyCode,
                                                                  @RequestParam(required = false) String exclude,
                                                                  OAuth2AuthenticationToken authentication,
                                                                  HttpSession session) {
        sessionHelper.validateOwnership(partyCode, authentication, session);
        return djService.findNextPlayableGuestTrack(partyCode, parseExcludeIds(exclude))
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    private static Set<Long> parseExcludeIds(String exclude) {
        if (exclude == null || exclude.isBlank()) {
            return Set.of();
        }
        Set<Long> ids = new HashSet<>();
        for (String part : exclude.split(",")) {
            try {
                ids.add(Long.parseLong(part.trim()));
            } catch (NumberFormatException ignored) {
                // malformed id from the client — ignore it rather than fail the whole lookup
            }
        }
        return ids;
    }

    /**
     * Extracts a YouTube playlist ID or video ID from a URL or raw input.
     * <p>
     * Supported formats:
     * <ul>
     *     <li>Playlist URL: {@code https://youtube.com/playlist?list=PLxxx} → {@code PLxxx}</li>
     *     <li>Watch URL with playlist: {@code https://youtube.com/watch?v=abc&list=PLxxx} → {@code PLxxx}</li>
     *     <li>Watch URL (single video): {@code https://youtube.com/watch?v=KD5fLb-WgBU} → {@code V:KD5fLb-WgBU}</li>
     *     <li>Short URL: {@code https://youtu.be/KD5fLb-WgBU?si=...} → {@code V:KD5fLb-WgBU}</li>
     *     <li>Raw playlist ID: {@code PLxxx} → {@code PLxxx}</li>
     *     <li>Raw video ID (11 chars): {@code KD5fLb-WgBU} → {@code V:KD5fLb-WgBU}</li>
     * </ul>
     * Video IDs are prefixed with {@code V:} so the frontend can distinguish them from playlist IDs
     * and use the correct YouTube IFrame Player API method.
     *
     * @return extracted ID (with {@code V:} prefix for single videos), or null if input is blank.
     */
    static String extractPlaylistId(String input) {
        if (input == null || input.isBlank()) return null;

        // Priority 1: playlist ID from URL (?list=PLxxx)
        Matcher playlistMatcher = PLAYLIST_ID_PATTERN.matcher(input);
        if (playlistMatcher.find()) return playlistMatcher.group(1);

        // Priority 2: video ID from watch URL (?v=xxx)
        Matcher videoMatcher = VIDEO_ID_V_PATTERN.matcher(input);
        if (videoMatcher.find()) return "V:" + videoMatcher.group(1);

        // Priority 3: video ID from short URL (youtu.be/xxx)
        Matcher shortMatcher = VIDEO_ID_SHORT_PATTERN.matcher(input);
        if (shortMatcher.find()) return "V:" + shortMatcher.group(1);

        // Priority 4: raw input — check if it looks like a video ID (exactly 11 chars, valid charset)
        String trimmed = input.trim();
        if (trimmed.matches("[A-Za-z0-9_-]{11}")) return "V:" + trimmed;

        // Otherwise treat as raw playlist ID (existing behavior)
        return trimmed;
    }
}

