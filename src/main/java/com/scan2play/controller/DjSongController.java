package com.scan2play.controller;

import com.scan2play.model.PlanFeature;
import com.scan2play.model.StaffPermission;
import com.scan2play.service.DeckHistoryService;
import com.scan2play.service.DjService;
import com.scan2play.service.PlanService;
import com.scan2play.service.SongRequestCommandService;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static com.scan2play.controller.ViewAttributes.REDIRECT_DASHBOARD;

/**
 * Controller responsible for song queue actions performed by the DJ.
 * <p>
 * Every action works on the party the page names ({@code partyCode}; none: the default panel, a page of an older version) and
 * checks what the person may do there ({@link DjSessionHelper#require}, V32).
 * <p>
 * Handles:
 * <ul>
 *     <li>Adding a song the DJ was asked for at the booth</li>
 *     <li>Marking songs as played — by the DJ, or by the DJ's program's play history read in the panel</li>
 *     <li>Skipping a song, clearing the queue</li>
 * </ul>
 * Dashboard views are handled by {@link DjDashboardController}.
 * Party settings are handled by {@link DjPartySettingsController}.
 */
@Controller
@RequestMapping("/dj")
@RequiredArgsConstructor
public class DjSongController {

    private final DjService djService;
    private final DjSessionHelper sessionHelper;
    private final PlanService planService;
    private final SongRequestCommandService songRequestCommandService;
    private final DeckHistoryService deckHistoryService;

    /**
     * Adds a song to the queue by hand ("Dodaj piosenkę", V44): a guest asked at the booth. For whom may work the queue; no AI,
     * no guest's limit ({@link SongRequestCommandService#addByTheDj}). Nothing typed: 400 — the page's field asks for a song first.
     *
     * @param song the song as typed or picked from the suggestions ("Artist – Title")
     * @return Redirects back to the dashboard.
     */
    @PostMapping("/dashboard/add-song")
    public String addSong(@RequestParam(defaultValue = "") String song, @RequestParam(required = false) String partyCode,
                          OAuth2AuthenticationToken authentication, HttpSession session) {
        String ownerPartyCode = sessionHelper.require(partyCode, StaffPermission.QUEUE, authentication, session).getPartyCode();
        if (song.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        songRequestCommandService.addByTheDj(ownerPartyCode, song);
        return REDIRECT_DASHBOARD;
    }

    /**
     * Marks a specific song request as "played".
     * Validates that the song belongs to the authenticated DJ's party (IDOR protection).
     *
     * @param id The ID of the song request to archive.
     * @return Redirects back to the dashboard.
     */
    @PostMapping("/dashboard/play")
    public String markAsPlayed(@RequestParam Long id, @RequestParam(required = false) String partyCode,
                               OAuth2AuthenticationToken authentication, HttpSession session) {
        String ownerPartyCode = sessionHelper.require(partyCode, StaffPermission.QUEUE, authentication, session).getPartyCode();
        djService.markSongAsPlayed(id, ownerPartyCode);
        return REDIRECT_DASHBOARD;
    }

    /** The songs the panel read from the DJ's program's play history (play-history.js): {@code at} in seconds since 1970, or none. */
    public record DeckPlays(String partyCode, List<DeckPlay> plays) {
    }

    /** One song as the program's history writes it. */
    public record DeckPlay(Long at, String artist, String title) {
    }

    /** The longest artist or title taken: more is no song's name (the keys cut at {@code LibraryKey.MAX} anyway). */
    static final int DECK_FIELD_MAX = 500;

    /**
     * "Zagrane samo" (docs/ideas.md item 18, variant E): the songs VirtualDJ or Serato played, read from its history by the panel in
     * the DJ's browser, mark the waiting requests they are played ({@link DeckHistoryService}) — for whom may work the queue, as
     * "Zagrane". At most {@value DeckHistoryService#MAX_PLAYS} songs, each field at most {@value #DECK_FIELD_MAX} characters: else 400.
     * Nothing of them is kept.
     *
     * @return the marked requests' song names, {@code {"marked": [...]}}
     */
    @PostMapping(value = "/dashboard/plays", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Map<String, List<String>> deckPlays(@RequestBody DeckPlays body, OAuth2AuthenticationToken authentication,
                                               HttpSession session) {
        String ownerPartyCode = sessionHelper.require(body.partyCode(), StaffPermission.QUEUE, authentication, session).getPartyCode();
        List<DeckPlay> plays = body.plays() == null ? List.of() : body.plays();
        if (plays.size() > DeckHistoryService.MAX_PLAYS || plays.stream().anyMatch(play -> play == null
                || tooLong(play.artist()) || tooLong(play.title()))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        List<DeckHistoryService.Play> songs = plays.stream()
                .map(play -> new DeckHistoryService.Play(play.at() == null ? null : Instant.ofEpochSecond(play.at()), play.artist(),
                        play.title()))
                .toList();
        return Map.of("marked", deckHistoryService.markPlayed(ownerPartyCode, songs, Instant.now()));
    }

    private static boolean tooLong(String field) {
        return field != null && field.length() > DECK_FIELD_MAX;
    }

    /**
     * Skips a waiting song request: it leaves the queue as rejected (a song the DJ does not have, or does not want now).
     * Validates that the song belongs to the authenticated DJ's party (IDOR protection).
     *
     * @param id The ID of the song request.
     * @return Redirects back to the dashboard.
     */
    @PostMapping("/dashboard/dismiss")
    public String dismiss(@RequestParam Long id, @RequestParam(required = false) String partyCode,
                          OAuth2AuthenticationToken authentication, HttpSession session) {
        String ownerPartyCode = sessionHelper.require(partyCode, StaffPermission.QUEUE, authentication, session).getPartyCode();
        djService.dismissSong(id, ownerPartyCode);
        return REDIRECT_DASHBOARD;
    }

    /**
     * Puts a request the DJ skipped or marked played back in the queue ("Cofnij" right after it, "↩ Przywróć" in the history). Only
     * the authenticated DJ's own party, only a skipped or a played request ({@link DjService#restoreSong}).
     *
     * @param id The ID of the song request.
     * @return Redirects back to the dashboard (the standalone history page's form lands there, with the song in the queue).
     */
    @PostMapping("/dashboard/restore")
    public String restore(@RequestParam Long id, @RequestParam(required = false) String partyCode,
                          OAuth2AuthenticationToken authentication, HttpSession session) {
        String ownerPartyCode = sessionHelper.require(partyCode, StaffPermission.QUEUE, authentication, session).getPartyCode();
        djService.restoreSong(id, ownerPartyCode);
        return REDIRECT_DASHBOARD;
    }

    /**
     * Counts a tip for a song ("💸", V28) — or takes one back ({@code add=false}) — at a song in the queue. Only the
     * authenticated DJ's own party: the party code comes from the session, the song must be one of its ({@link DjService#countTip}).
     *
     * @param id The ID of the song request.
     * @return Redirects back to the dashboard.
     */
    @PostMapping("/dashboard/tip-count")
    public String countTip(@RequestParam Long id, @RequestParam(defaultValue = "true") boolean add,
                           @RequestParam(required = false) String partyCode,
                           OAuth2AuthenticationToken authentication, HttpSession session) {
        String ownerPartyCode = planService.require(sessionHelper.require(partyCode, StaffPermission.TIPS, authentication, session),
                PlanFeature.TIPS).getPartyCode();
        djService.countTip(id, ownerPartyCode, add);
        return REDIRECT_DASHBOARD;
    }

    /**
     * Clears the DJ's queue: every waiting request leaves it as rejected (it stays in the history). Only the authenticated DJ's own
     * party — the party code comes from the session, never from the form.
     *
     * @return Redirects back to the dashboard.
     */
    @PostMapping("/dashboard/clear-queue")
    public String clearQueue(@RequestParam(required = false) String partyCode, OAuth2AuthenticationToken authentication,
                             HttpSession session) {
        String ownerPartyCode = sessionHelper.require(partyCode, StaffPermission.CLEAR_QUEUE, authentication, session).getPartyCode();
        djService.clearQueue(ownerPartyCode);
        return REDIRECT_DASHBOARD;
    }

    /**
     * Clears the DJ's history: the requests that played or were rejected are deleted ({@link DjService#clearHistory}). Only the
     * authenticated DJ's own party — the party code comes from the session, never from the form.
     *
     * @return Redirects to the standalone history page (the dashboard's History tab sends it in the background and reloads the tab).
     */
    @PostMapping("/dashboard/clear-history")
    public String clearHistory(@RequestParam(required = false) String partyCode, OAuth2AuthenticationToken authentication,
                             HttpSession session) {
        // the owner alone (V30): the staff work the queue, the history is the owner's record of the evenings
        String ownerPartyCode = sessionHelper.requireOwner(partyCode, authentication, session).getPartyCode();
        djService.clearHistory(ownerPartyCode);
        return "redirect:/dj/history-view?party=" + ownerPartyCode;
    }
}

