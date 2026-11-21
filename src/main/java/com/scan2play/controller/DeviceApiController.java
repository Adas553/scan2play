package com.scan2play.controller;

import com.scan2play.model.PlanFeature;
import com.scan2play.service.DeckHistoryService;
import com.scan2play.service.DjDeviceService;
import com.scan2play.service.GuestRequestLimiter;
import com.scan2play.service.PartySettingsQueryService;
import com.scan2play.service.PlanService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The DJ's program on the DJ's computer ("Program na komputerze", docs/ideas.md item 19, V78): it pairs with a party by the code the
 * panel shows, then sends the songs VirtualDJ or Serato played — the same as the panel's "Zagrane samo" (variant E), with its own
 * token instead of the DJ's session. Public (no login, no CSRF: no cookie is used — SecurityConfig); a token unknown or revoked
 * ("Odłącz") is a 401, too many calls a 429, a party whose organiser has no Pro (any more) a 403 {@code {"error": "plan"}}. The
 * program's own "Odłącz ten komputer" forgets its computer here too ({@code /unpair}).
 */
@RestController
@RequestMapping(value = "/api/device", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class DeviceApiController {

    private static final String BEARER = "Bearer ";

    private final DjDeviceService devices;
    private final DeckHistoryService deckHistory;
    private final GuestRequestLimiter limiter;
    private final PartySettingsQueryService parties;
    private final PlanService plans;

    /** The program's first call: the panel's code and the computer's name. */
    public record PairRequest(String code, String name) {
    }

    /**
     * The songs the program read since its last call, as the panel's {@link DjSongController.DeckPlay}, and its clock as it sent them
     * (seconds since 1970; {@link DjSongController#songsOf}).
     */
    public record PlaysRequest(List<DjSongController.DeckPlay> plays, Long sentAt) {
    }

    /**
     * The code swapped for the program's token: {@code {"token": "…"}}; a wrong, used or expired code 400 {@code {"error": "code"}},
     * too many tries 429 {@code {"error": "tries"}}.
     */
    @PostMapping(value = "/pair", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, String>> pair(@RequestBody PairRequest body, HttpServletRequest request) {
        DjDeviceService.Pairing pairing = devices.pair(body.code(), body.name(), limiter.clientIp(request), Instant.now());
        if (pairing.token() != null) {
            return ResponseEntity.ok(Map.of("token", pairing.token()));
        }
        return pairing.refusal() == DjDeviceService.PairRefusal.TOO_MANY_TRIES
                ? ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(Map.of("error", "tries"))
                : ResponseEntity.badRequest().body(Map.of("error", "code"));
    }

    /**
     * The songs that played: the waiting requests of them marked played ({@link DeckHistoryService}) — {@code {"marked": [...]}}. An
     * empty list is the program's "still paired?" ({@code {"marked": []}}). At most {@value DeckHistoryService#MAX_PLAYS} songs,
     * each field at most {@value DjSongController#DECK_FIELD_MAX} characters, else 400.
     */
    @PostMapping(value = "/plays", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, ?>> plays(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
                                                           @RequestBody PlaysRequest body) {
        Instant now = Instant.now();
        String token = bearer(authorization);
        Optional<DjDeviceService.Paired> paired = token != null ? devices.device(token, now) : Optional.empty();
        if (paired.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (!devices.mayCall(paired.get().deviceId())) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).build();
        }
        // the party's organiser without Pro (it ended): the program keeps its token and says so, the songs are not looked at
        if (!plans.allows(parties.getSettings(paired.get().partyCode()), PlanFeature.DECK)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "plan"));
        }
        List<DjSongController.DeckPlay> plays = body.plays() == null ? List.of() : body.plays();
        if (!DjSongController.validPlays(plays)) {
            return ResponseEntity.badRequest().build();
        }
        List<DeckHistoryService.Play> songs = DjSongController.songsOf(plays, body.sentAt(), now);
        return ResponseEntity.ok(Map.of("marked", deckHistory.markPlayed(paired.get().partyCode(), songs, now,
                DjSongController.clockKnown(body.sentAt()))));
    }

    /**
     * The program's "Odłącz ten komputer": its computer gone from the party's list, its token dead — 204; a token unknown 401 (the
     * program forgets it either way). Any plan: without Pro the panel shows no list to take a computer away from.
     */
    @PostMapping("/unpair")
    public ResponseEntity<Void> unpair(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        String token = bearer(authorization);
        return token != null && devices.removeByToken(token)
                ? ResponseEntity.noContent().build() : ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }

    private static String bearer(String authorization) {
        return authorization != null && authorization.startsWith(BEARER) ? authorization.substring(BEARER.length()).strip() : null;
    }
}
