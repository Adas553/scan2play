package com.scan2play.controller;

import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.model.NextGuestTrackResponse;
import com.scan2play.model.NextTrackResponse;
import com.scan2play.service.DjService;
import com.scan2play.service.NextTrackService;
import com.scan2play.service.PartySettingsQueryService;
import com.scan2play.service.PlayerLeaseService;
import com.scan2play.service.QrCodeService;
import com.scan2play.util.YouTubeUrls;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import static com.scan2play.controller.ViewAttributes.*;

import java.util.HashSet;
import java.util.Set;

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

    private final DjService djService;
    private final PartySettingsQueryService partySettingsQueryService;
    private final QrCodeService qrCodeService;
    private final DjSessionHelper sessionHelper;
    private final NextTrackService nextTrackService;
    private final PlayerLeaseService playerLeaseService;

    /** The history shows this many requests at first, and this many more each time the DJ asks for more. */
    static final int HISTORY_PAGE_SIZE = 50;

    /** The furthest back the history goes: the query stays bounded, and the list stays a list a person can use. */
    static final int HISTORY_MAX_LIMIT = 300;

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
     * Displays the history of played and rejected songs — the last {@code limit} requests (see {@link #addHistory}).
     */
    @GetMapping("/history-view")
    public String historyView(@RequestParam(defaultValue = "" + HISTORY_PAGE_SIZE) int limit, Model model,
                              OAuth2AuthenticationToken authentication, HttpSession session) {
        if (authentication == null) {
            return REDIRECT_LOGIN;
        }
        PartySettingsEntity settings = sessionHelper.getPartySettings(authentication, session);
        String partyCode = settings.getPartyCode();

        model.addAttribute(PARTY_CODE, partyCode);
        model.addAttribute(IS_ACTIVE, settings.isActive());
        addHistory(model, partyCode, limit);

        return "history";
    }

    /**
     * Returns the history table as an HTML fragment for AJAX-based tab switching.
     * Used by the dashboard to load history without a full page reload,
     * which preserves the YouTube IFrame player state. "Show more" asks for the same fragment with a larger
     * {@code limit}.
     */
    @GetMapping("/history-view/fragment")
    public String historyFragment(@RequestParam String partyCode,
                                  @RequestParam(defaultValue = "" + HISTORY_PAGE_SIZE) int limit, Model model,
                                  OAuth2AuthenticationToken authentication, HttpSession session) {
        sessionHelper.validateOwnership(partyCode, authentication, session);
        addHistory(model, partyCode, limit);
        return "history :: historyTableContent";
    }

    /**
     * Puts the last {@code limit} requests of the history in the model — at least one page, at most
     * {@value #HISTORY_MAX_LIMIT} (the query is always bounded) — and what the "Show more" button needs: whether
     * there are older ones to show and the limit to ask for next.
     */
    private void addHistory(Model model, String partyCode, int requestedLimit) {
        int limit = Math.max(HISTORY_PAGE_SIZE, Math.min(requestedLimit, HISTORY_MAX_LIMIT));
        DjService.HistoryPage page = djService.getHistory(partyCode, limit);
        model.addAttribute(HISTORY, page.rows());
        model.addAttribute(HISTORY_HAS_MORE, page.hasMore() && limit < HISTORY_MAX_LIMIT);
        model.addAttribute(HISTORY_NEXT_LIMIT, Math.min(limit + HISTORY_PAGE_SIZE, HISTORY_MAX_LIMIT));
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
     * Read-only "is a guest song waiting?" peek (Section 14, Phase 1).
     * <p>
     * Returns the oldest accepted guest song with a playable video ID, or 204 No Content
     * when none is ready. It replaced the old client-side DOM scan of the queue table; since
     * Phase 2 stage 4 the Auto-Pilot client asks {@link #nextTrack} instead (guest song or
     * background track), so nothing calls this endpoint any more. Read-only: guest playback is
     * confirmed via the existing {@code POST /dj/dashboard/play} once the video actually starts.
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

    /**
     * Server-side "what plays next?" for YouTube Auto-Pilot: a waiting guest song first, otherwise the next
     * track of the party's fallback (background music) playlist — see {@link NextTrackService}.
     * <p>
     * A POST because it is <b>not</b> read-only: a background track is marked as played the moment it is
     * handed out (a guest song is still confirmed later via {@code POST /dj/dashboard/play}). Ask for it only
     * when a track is actually about to be loaded, not to poll.
     * <p>
     * Returns 204 No Content when there is neither a guest song nor a background track, and 409 Conflict when
     * another dashboard window holds the party's player lease ({@link PlayerLeaseService}) — nothing is handed out
     * then, so a window that only looks at the dashboard cannot take tracks off the queue.
     *
     * @param exclude  Optional comma-separated guest song IDs the client already knows are broken (the YouTube
     *                 player itself errored on them) and wants skipped.
     * @param deviceId The asking window's id, as it reports it to {@code /dashboard/player-lease}.
     */
    @PostMapping("/dashboard/next-track")
    @ResponseBody
    public ResponseEntity<NextTrackResponse> nextTrack(@RequestParam String partyCode,
                                                       @RequestParam(required = false) String exclude,
                                                       @RequestParam(required = false) String deviceId,
                                                       OAuth2AuthenticationToken authentication,
                                                       HttpSession session) {
        sessionHelper.validateOwnership(partyCode, authentication, session);
        if (!playerLeaseService.mayPlay(partyCode, deviceId)) {
            return ResponseEntity.status(409).build();
        }
        return nextTrackService.findNextTrack(partyCode, parseExcludeIds(exclude))
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
        return YouTubeUrls.extractPlaylistId(input);
    }
}

