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
 * panel shows, then sends the songs VirtualDJ or Serato played, with its own token instead of the DJ's session. Public (no login, no CSRF: no cookie is used — SecurityConfig); a token unknown or revoked
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
     * The songs the program read since its last call, and its clock as it sent them (seconds since 1970; {@link #songsOf}).
     */
    public record PlaysRequest(List<DeckPlay> plays, Long sentAt) {
    }

    /**
     * One song as the DJ's program's history writes it: {@code at} in seconds since 1970, or none; {@code program} what it was read
     * from ("VirtualDJ"), none from a program before V81.
     */
    public record DeckPlay(Long at, String artist, String title, String program) {

        public DeckPlay(Long at, String artist, String title) {
            this(at, artist, title, null);
        }
    }

    /** The longest artist or title taken: more is no song's name (the keys cut at {@code LibraryKey.MAX} anyway). */
    static final int DECK_FIELD_MAX = 500;

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
     * each field at most {@value #DECK_FIELD_MAX} characters, else 400.
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
        List<DeckPlay> plays = body.plays() == null ? List.of() : body.plays();
        if (!validPlays(plays)) {
            return ResponseEntity.badRequest().build();
        }
        List<DeckHistoryService.Play> songs = songsOf(plays, body.sentAt(), now);
        span(songs, now).ifPresent(span -> devices.notePlay(paired.get().deviceId(), span.oldest(), span.newest(),
                programOfNewest(plays, songs, now)));
        return ResponseEntity.ok(Map.of("marked", deckHistory.markPlayed(paired.get().partyCode(), songs, now,
                clockKnown(body.sentAt()))));
    }

    /** The first and the last start of the songs of one call. */
    record Span(Instant oldest, Instant newest) {
    }

    /**
     * The oldest and the newest song's start (one without a time: now; one "later" than now: now), for the panel's "ostatnia piosenka"
     * (V81) and the run of songs (V82); none when every song is older than {@link DeckHistoryService#OLDEST} — a history read again,
     * not tonight's set (such songs are left out of the span too).
     */
    static Optional<Span> span(List<DeckHistoryService.Play> songs, Instant now) {
        List<Instant> starts = songs.stream().map(song -> startOf(song, now))
                .filter(at -> !at.isBefore(now.minus(DeckHistoryService.OLDEST))).sorted().toList();
        return starts.isEmpty() ? Optional.empty() : Optional.of(new Span(starts.getFirst(), starts.getLast()));
    }

    private static Instant startOf(DeckHistoryService.Play song, Instant now) {
        return song.at() == null || song.at().isAfter(now) ? now : song.at();
    }

    /** The program the newest song came from (the same order as {@code songs}); null when it did not say. */
    private static String programOfNewest(List<DeckPlay> plays, List<DeckHistoryService.Play> songs, Instant now) {
        int newest = 0;
        for (int i = 1; i < songs.size(); i++) {
            if (!startOf(songs.get(i), now).isBefore(startOf(songs.get(newest), now))) {
                newest = i;
            }
        }
        return plays.get(newest).program();
    }

    /** At most {@value DeckHistoryService#MAX_PLAYS} songs, none null, each field at most {@value #DECK_FIELD_MAX} characters. */
    static boolean validPlays(List<DeckPlay> plays) {
        return plays.size() <= DeckHistoryService.MAX_PLAYS && plays.stream().noneMatch(play -> play == null
                || tooLong(play.artist()) || tooLong(play.title()) || tooLong(play.program()));
    }

    /**
     * The songs as the service takes them; a time out of {@link Instant}'s range is taken as none (now). A song's time is the DJ's
     * computer's clock, which may be minutes off: when the program says what its clock showed as it sent ({@code sentAt}), every time
     * is moved by the difference from the server's {@code now} — a computer whose clock runs 8 minutes slow would otherwise play every
     * song "before the guest asked" ({@link DeckHistoryService}) and mark nothing. Without it, the times are taken as they are.
     */
    static List<DeckHistoryService.Play> songsOf(List<DeckPlay> plays, Long sentAt, Instant now) {
        long skew = clockKnown(sentAt) ? now.getEpochSecond() - sentAt : 0;
        return plays.stream()
                .map(play -> new DeckHistoryService.Play(play.at() == null || play.at() < 0 || play.at() > 100_000_000_000L
                        ? null : Instant.ofEpochSecond(play.at() + skew), play.artist(), play.title()))
                .toList();
    }

    /** Whether the program said its clock ({@code sentAt} in {@link Instant}'s range): the songs' times are then the server's. */
    static boolean clockKnown(Long sentAt) {
        return sentAt != null && sentAt >= 0 && sentAt <= 100_000_000_000L;
    }

    private static boolean tooLong(String field) {
        return field != null && field.length() > DECK_FIELD_MAX;
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
