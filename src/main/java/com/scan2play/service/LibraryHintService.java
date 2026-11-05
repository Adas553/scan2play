package com.scan2play.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.model.LibraryMark;
import com.scan2play.model.PlanFeature;
import com.scan2play.util.LibraryKey;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/**
 * The guests' song suggestions marked by the DJ's library (V58): of the few suggestions a guest is shown as they type, the browser
 * asks which the DJ has, and puts those first with "✓ DJ ma" (song-autocomplete.js). Only "✓" is told — never "? Podobny", a guess a
 * guest would read as a promise — and for at most {@value #MOST_SONGS} songs at a time, {@value #ASKS_A_MINUTE} asks a minute of an
 * address ({@link #mayAsk}): the library itself is never listed, and a script cannot read it out quickly. Nothing is refused by it; a
 * song the DJ has not is asked for as before.
 */
@Service
@RequiredArgsConstructor
public class LibraryHintService {

    /** The most songs one ask may name: the suggestions shown (8), with room. */
    public static final int MOST_SONGS = 10;

    /** The longest name of a song taken: an artist and a title, with room (a key is cut to its columns anyway). */
    static final int LONGEST_SONG = 300;

    /**
     * The asks an address may make of one party in a minute: a guest's typing asks once per new set of suggestions (the browser keeps
     * the answers), a venue's Wi-Fi is many guests; a script reading the library ten songs at a time is slowed to 1 200 an address.
     */
    public static final int ASKS_A_MINUTE = 120;
    private static final long WINDOW_NANOS = Duration.ofMinutes(1).toNanos();

    /** One address's asks of one party since {@code start} ({@link System#nanoTime}). */
    private record Window(long start, int count) {
    }

    private final DjLibraryService libraryService;
    private final PlanService planService;
    /** "party|address" → its minute's asks; in memory, as the guests' other limits — gone a while after the last ask. */
    private final Cache<String, Window> asks = Caffeine.newBuilder().expireAfterAccess(Duration.ofMinutes(2)).maximumSize(20_000).build();

    /**
     * Whether the party's guests have their suggestions marked: an open party whose organiser has the plan with the library, has one,
     * and has not switched the marks off.
     */
    public boolean on(PartySettingsEntity party) {
        return party.isActive() && party.isLibraryHints() && planService.allows(party, PlanFeature.LIBRARY)
                && libraryService.info(party.getPartyCode()).isPresent();
    }

    /** Counts one ask of {@code client} (an address) of the party's library; false past {@value #ASKS_A_MINUTE} this minute. */
    public boolean mayAsk(String partyCode, String client) {
        long now = System.nanoTime();
        Window window = asks.asMap().compute(partyCode + "|" + client, (key, had) -> had == null || now - had.start() >= WINDOW_NANOS
                ? new Window(now, 1) : new Window(had.start(), had.count() + 1));
        return window.count() <= ASKS_A_MINUTE;
    }

    /**
     * For each of the songs ("Artist - Title", as the suggestions name them), whether the party's library has it ("✓": the title and
     * an artist in common) — one lookup for all of them. At most {@value #MOST_SONGS} are looked at, each cut to
     * {@value #LONGEST_SONG} characters; the answer is as long as what was looked at. The caller checks {@link #on} first.
     */
    public List<Boolean> have(String partyCode, List<String> songs) {
        List<LibraryKey> keys = songs.stream().limit(MOST_SONGS)
                .map(song -> LibraryKey.of(song.length() > LONGEST_SONG ? song.substring(0, LONGEST_SONG) : song)).toList();
        if (keys.isEmpty()) {
            return List.of();
        }
        Map<LibraryKey, LibraryMark> marks = libraryService.marks(partyCode, new HashSet<>(keys));
        return keys.stream().map(key -> marks.get(key) == LibraryMark.HAVE).toList();
    }
}
