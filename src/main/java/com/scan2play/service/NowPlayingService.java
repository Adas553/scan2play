package com.scan2play.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * "🎶 Teraz: …" on the TV screen and the kiosk (docs/ideas.md item 21, "Teraz leci"): the guest's request the DJ's program has just
 * played — set only by {@link DeckHistoryService}, never by the DJ's own "Zagrane", which is often tapped minutes later, when "now"
 * would be untrue. Kept in memory, not in the database: the TV and the kiosk fetch their lists every few seconds, and this costs them
 * a map's look-up instead of a query; a restart forgets at most one song's bar.
 * <p>
 * A song is "now" from its start ({@code startedAt}, the program's time on the server's clock) for {@link #SHOWN}, and only until a
 * later song reaches the server from the party's program — the next song ends it (VirtualDJ writes a song ~45 s after its start, so
 * the bar stays that much into the next one; Serato 2/3 writes a song when it is loaded, so the bar may go a little early).
 */
@Service
public class NowPlayingService {

    /** The longest a song is "now": a long song, or the next one not seen (the program stopped, the DJ talks). */
    public static final Duration SHOWN = Duration.ofMinutes(6);

    /**
     * A song sent again (the program restarted: the last minutes go again; the clock's skew moved by a second) is not "the next
     * one": only a song that started this much after the one shown ends it.
     */
    static final Duration SAME_SONG = Duration.ofSeconds(30);

    /** The song shown and when it started. */
    public record Song(String name, Instant startedAt) {
    }

    private final Cache<String, Song> songs = Caffeine.newBuilder()
            .expireAfterWrite(SHOWN).maximumSize(10_000).build();

    /**
     * The songs of one call of the party's program: {@code marked} the guest's request it played (null: none), {@code newestStart}
     * the start of the newest song of the call. A request whose song is not the newest of the call is over already (a history read
     * again); a song later than the one shown ends it.
     */
    public void heard(String partyCode, Song marked, Instant newestStart) {
        if (marked != null && !newestStart.isAfter(marked.startedAt().plus(SAME_SONG))) {
            songs.put(partyCode, marked);
            return;
        }
        Song shown = songs.getIfPresent(partyCode);
        if (shown != null && newestStart.isAfter(shown.startedAt().plus(SAME_SONG))) {
            songs.asMap().remove(partyCode, shown);
        }
    }

    /** The party's song playing now, if the program played a guest's request in the last {@link #SHOWN}. */
    public Optional<Song> now(String partyCode, Instant now) {
        Song song = songs.getIfPresent(partyCode);
        return song != null && now.isBefore(song.startedAt().plus(SHOWN)) ? Optional.of(song) : Optional.empty();
    }
}
