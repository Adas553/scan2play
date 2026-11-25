package com.scan2play.deck;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Spotify as a history: what plays long enough becomes a played song, once; what Spotify's window and AppleScript say. */
class SpotifyNowPlayingTest {

    private static final Instant START = Instant.parse("2026-11-25T20:00:00Z");

    /** Spotify as the test sets it. */
    private static final class FakePlayer implements SpotifyNowPlaying.Player {
        boolean running = true;
        SpotifyNowPlaying.Track track;

        @Override public boolean running() { return running; }
        @Override public Optional<SpotifyNowPlaying.Track> now() { return Optional.ofNullable(track); }

        void plays(String artist, String title) {
            track = new SpotifyNowPlaying.Track(artist, title);
        }
    }

    /** A clock the test moves. */
    private static final class Ticking extends Clock {
        Instant now = START;

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private final FakePlayer player = new FakePlayer();
    private final Ticking clock = new Ticking();
    private final SpotifyNowPlaying spotify = new SpotifyNowPlaying(player, clock);

    /** Looks every 3 s, as the agent does, for that long. */
    private void looksFor(Duration duration) {
        Instant end = clock.now.plus(duration);
        while (clock.now.isBefore(end)) {
            clock.now = clock.now.plusSeconds(3);
            spotify.version();
        }
    }

    @Test
    void aSongHeard30Seconds_isPlayed_onceFromItsStart() {
        player.plays("Sanah", "Szampan");
        spotify.version();
        looksFor(Duration.ofSeconds(27));
        assertEquals(List.of(), spotify.songs(), "27 s: not yet");
        String before = spotify.version();
        looksFor(Duration.ofSeconds(3));
        assertEquals(List.of(new Played(START, "Sanah", "Szampan")), spotify.songs());
        assertEquals(false, before.equals(spotify.version()), "a new song played changes the version: the agent reads again");
        looksFor(Duration.ofMinutes(3));
        assertEquals(1, spotify.songs().size(), "the same song going on is one play");
    }

    @Test
    void songsSkimmedThrough_areNotPlayed() {
        for (String title : List.of("Jeden", "Dwa", "Trzy", "Cztery")) {
            player.plays("Artysta", title);
            looksFor(Duration.ofSeconds(15));
        }
        assertEquals(List.of(), spotify.songs());
    }

    @Test
    void aPause_stopsTheCount_andItGoesOnAfter() {
        player.plays("Dawid Podsiadło", "Małomiasteczkowy");
        spotify.version();
        looksFor(Duration.ofSeconds(18));
        player.track = null;                       // "Spotify Premium": paused
        looksFor(Duration.ofMinutes(5));
        assertEquals(List.of(), spotify.songs(), "the pause is not heard");
        player.plays("Dawid Podsiadło", "Małomiasteczkowy");
        looksFor(Duration.ofSeconds(12));
        assertEquals(List.of(), spotify.songs(), "the look that finds it playing again starts counting: 18 + 9 s");
        looksFor(Duration.ofSeconds(3));
        assertEquals(List.of(new Played(START, "Dawid Podsiadło", "Małomiasteczkowy")), spotify.songs(), "18 + 12 s heard");
    }

    @Test
    void theSameSongAgainStraightAfter_isNotASecondPlay_butAfterAnotherOneItIs() {
        player.plays("A", "Raz");
        looksFor(Duration.ofSeconds(40));
        player.track = null;
        looksFor(Duration.ofSeconds(6));
        player.plays("A", "Raz");                  // a pause read as "Spotify Premium", then the same song
        looksFor(Duration.ofSeconds(40));
        assertEquals(1, spotify.songs().size());
        player.plays("B", "Dwa");
        looksFor(Duration.ofSeconds(40));
        player.plays("A", "Raz");
        looksFor(Duration.ofSeconds(40));
        assertEquals(List.of("Raz", "Dwa", "Raz"), spotify.songs().stream().map(Played::title).toList());
    }

    @Test
    void aComputerAsleep_addsNoTimeHeard() {
        player.plays("A", "Raz");
        spotify.version();
        clock.now = clock.now.plus(Duration.ofHours(1));
        spotify.version();
        assertEquals(List.of(), spotify.songs(), "only " + SpotifyNowPlaying.LONGEST_GAP + " counted for the gap");
    }

    @Test
    void spotifyNotRunning_hasNoVersion_soTheWindowDoesNotListIt() {
        player.running = false;
        player.plays("A", "Raz");
        assertEquals("", spotify.version());
        looksFor(Duration.ofMinutes(1));
        assertEquals(List.of(), spotify.songs());
        player.running = true;
        assertEquals("spotify:0", spotify.version());
        assertEquals("gra: A - Raz", spotify.reading());
        player.track = null;
        spotify.version();
        assertEquals("nic nie gra", spotify.reading());
    }

    @Test
    void theAgent_sendsASpotifySongOnce(@TempDir Path dir) throws Exception {
        List<List<Played>> sent = new java.util.ArrayList<>();
        ServerClient server = new ServerClient("test") {
            @Override
            Answer plays(String address, String token, List<Played> songs) {
                sent.add(songs);
                return new Answer(Outcome.OK, token, List.of());
            }
        };
        Settings settings = new Settings(dir.resolve("zagrane.properties"));
        settings.paired("https://example.test", "tok");
        Agent agent = new Agent(List.of(spotify), server, settings, clock, status -> { });
        player.plays("Sanah", "Szampan");
        for (int i = 0; i < 30; i++) {
            clock.now = clock.now.plusSeconds(3);
            agent.tick();
        }
        assertEquals(List.of(new Played(START.plusSeconds(3), "Sanah", "Szampan")),
                sent.stream().flatMap(List::stream).toList());
    }

    @Test
    void theWindowTitle_isArtistAndTitle_orNothing() {
        assertEquals(Optional.of(new SpotifyNowPlaying.Track("Sanah", "Szampan")), SpotifyNowPlaying.fromWindowTitle("Sanah - Szampan"));
        assertEquals(Optional.of(new SpotifyNowPlaying.Track("Queen", "Don't Stop Me Now - Remastered 2011")),
                SpotifyNowPlaying.fromWindowTitle("Queen - Don't Stop Me Now - Remastered 2011"));
        assertEquals(Optional.of(new SpotifyNowPlaying.Track("Kult", "Arahja")), SpotifyNowPlaying.fromWindowTitle(" Kult - Arahja \r"));
        for (String paused : List.of("Spotify Premium", "Spotify Free", "Spotify", "Advertisement", "Spotify - Advertisement", "", "N/A")) {
            assertEquals(Optional.empty(), SpotifyNowPlaying.fromWindowTitle(paused), paused);
        }
        assertEquals(Optional.empty(), SpotifyNowPlaying.fromWindowTitle(null));
    }

    @Test
    void appleScriptsAnswer_isArtistTabTitle_orNothing() {
        assertEquals(Optional.of(new SpotifyNowPlaying.Track("Daria Zawiałow", "Kinga")),
                SpotifyNowPlaying.fromAppleScript("Daria Zawiałow\tKinga\n"));
        assertEquals(Optional.empty(), SpotifyNowPlaying.fromAppleScript("\n"));
        assertEquals(Optional.empty(), SpotifyNowPlaying.fromAppleScript(""));
        assertEquals(Optional.empty(), SpotifyNowPlaying.fromAppleScript("Artysta\t"));
    }
}
