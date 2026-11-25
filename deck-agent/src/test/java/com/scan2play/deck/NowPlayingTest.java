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
class NowPlayingTest {

    private static final Instant START = Instant.parse("2026-11-25T20:00:00Z");

    /** Spotify as the test sets it. */
    private static final class FakePlayer implements NowPlaying.Player {
        boolean running = true;
        NowPlaying.Track track;

        @Override public boolean running() { return running; }
        String why = NowPlaying.NOTHING;

        @Override public NowPlaying.Heard now() { return new NowPlaying.Heard(track, track == null ? why : ""); }

        void plays(String artist, String title) {
            track = new NowPlaying.Track(artist, title);
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
    private final NowPlaying spotify = new NowPlaying("Spotify", player, clock);

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
        player.track = null;                       // "Spotify Premium": paused for a moment
        looksFor(Duration.ofMinutes(1));
        assertEquals(List.of(), spotify.songs(), "the pause is not heard");
        player.plays("Dawid Podsiadło", "Małomiasteczkowy");
        looksFor(Duration.ofSeconds(12));
        assertEquals(List.of(), spotify.songs(), "the look that finds it playing again starts counting: 18 + 9 s");
        looksFor(Duration.ofSeconds(3));
        assertEquals(List.of(new Played(START, "Dawid Podsiadło", "Małomiasteczkowy")), spotify.songs(), "18 + 12 s heard");
    }

    @Test
    void aShortPause_keepsTheSong_aLongOne_makesItANewPlayFromWhenItPlaysAgain() {
        player.plays("A", "Raz");
        looksFor(Duration.ofSeconds(20));
        player.track = null;                                   // paused for another program's set
        looksFor(Duration.ofMinutes(45));
        player.plays("A", "Raz");
        Instant again = clock.now.plusSeconds(3);
        looksFor(Duration.ofSeconds(12));
        assertEquals(List.of(), spotify.songs(), "the 20 s before the pause are not counted any more");
        looksFor(Duration.ofSeconds(21));
        assertEquals(List.of(new Played(again, "A", "Raz")), spotify.songs(), "its start: when it played again, not 45 minutes before");
        player.track = null;
        looksFor(Duration.ofMinutes(10));
        player.plays("A", "Raz");                              // the same song again, an hour later: a second play
        looksFor(Duration.ofSeconds(33));
        assertEquals(2, spotify.songs().size());
    }

    @Test
    void anotherSongClickedForAFewSeconds_andBack_isOnePlay() {
        player.plays("A", "Raz");
        looksFor(Duration.ofSeconds(40));
        player.plays("B", "Dwa");
        looksFor(Duration.ofSeconds(9));
        player.plays("A", "Raz");
        looksFor(Duration.ofSeconds(40));
        assertEquals(List.of("Raz"), spotify.songs().stream().map(Played::title).toList());
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
        assertEquals(List.of(), spotify.songs(), "only " + NowPlaying.LONGEST_GAP + " counted for the gap");
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
    void spotifySilentForAReason_saysIt() {
        player.why = NowPlaying.HIDDEN;
        spotify.version();
        assertEquals(NowPlaying.HIDDEN, spotify.reading());
        player.why = NowPlaying.NO_CONSENT;
        spotify.version();
        assertEquals(NowPlaying.NO_CONSENT, spotify.reading());
        player.plays("", "Odcinek 12");                        // a podcast: no artist
        spotify.version();
        assertEquals("gra: Odcinek 12", spotify.reading());
    }

    @Test
    void theVersion_staysWhileNoNewSongPlays_soTheAgentDoesNotReadItAtEachLook() {
        player.plays("A", "Raz");
        String first = spotify.version();
        looksFor(Duration.ofSeconds(27));
        assertEquals(first, spotify.version());
        looksFor(Duration.ofSeconds(3));
        String played = spotify.version();
        looksFor(Duration.ofMinutes(2));
        assertEquals(played, spotify.version());
    }

    @Test
    void spotifyClosedAndOpenedAgain_goesOnCounting_andSendsNothingTwice(@TempDir Path dir) throws Exception {
        List<Played> sent = new java.util.ArrayList<>();
        Agent agent = new Agent(List.of(spotify), server(sent), paired(dir), clock, status -> { });
        player.plays("A", "Raz");
        ticksFor(agent, Duration.ofSeconds(40));
        player.running = false;
        ticksFor(agent, Duration.ofMinutes(1));
        player.running = true;
        player.plays("B", "Dwa");
        ticksFor(agent, Duration.ofSeconds(40));
        assertEquals(List.of("Raz", "Dwa"), sent.stream().map(Played::title).toList());
        assertEquals("spotify:2", spotify.version());
    }

    @Test
    void onlyTheLastSongsAreKept() {
        for (int i = 0; i <= NowPlaying.KEPT; i++) {
            player.plays("A", "Piosenka " + i);
            looksFor(Duration.ofSeconds(33));
        }
        List<Played> songs = spotify.songs();
        assertEquals(NowPlaying.KEPT, songs.size());
        assertEquals("Piosenka 1", songs.getFirst().title());
        assertEquals("spotify:" + (NowPlaying.KEPT + 1), spotify.version(), "the version counts every play, kept or not");
    }

    private void ticksFor(Agent agent, Duration duration) {
        Instant end = clock.now.plus(duration);
        while (clock.now.isBefore(end)) {
            clock.now = clock.now.plusSeconds(3);
            agent.tick();
        }
    }

    private static ServerClient server(List<Played> sent) {
        return new ServerClient("test") {
            @Override
            Answer plays(String address, String token, List<Played> songs) {
                sent.addAll(songs);
                return new Answer(Outcome.OK, token, List.of());
            }
        };
    }

    private static Settings paired(Path dir) throws java.io.IOException {
        Settings settings = new Settings(dir.resolve("zagrane.properties"));
        settings.paired("https://example.test", "tok");
        return settings;
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
        assertEquals(Optional.of(new NowPlaying.Track("Sanah", "Szampan")), NowPlaying.fromWindowTitle("Sanah - Szampan"));
        assertEquals(Optional.of(new NowPlaying.Track("Queen", "Don't Stop Me Now - Remastered 2011")),
                NowPlaying.fromWindowTitle("Queen - Don't Stop Me Now - Remastered 2011"));
        assertEquals(Optional.of(new NowPlaying.Track("Kult", "Arahja")), NowPlaying.fromWindowTitle(" Kult - Arahja \r"));
        for (String paused : List.of("Spotify Premium", "Spotify Free", "Spotify", "Advertisement", "Spotify - Advertisement", "", "N/A")) {
            assertEquals(Optional.empty(), NowPlaying.fromWindowTitle(paused), paused);
        }
        assertEquals(Optional.empty(), NowPlaying.fromWindowTitle(null));
    }

    @Test
    void theWindow_paused_orClosedToTheTray() {
        assertEquals(NowPlaying.NOTHING, NowPlaying.fromWindow("Spotify Premium").why());
        assertEquals(NowPlaying.HIDDEN, NowPlaying.fromWindow("").why(), "no window title: closed to the tray");
        assertEquals(new NowPlaying.Track("Kult", "Arahja"), NowPlaying.fromWindow("Kult - Arahja").track());
    }

    @Test
    void osascriptsAnswer_aSong_nothing_orNoConsent() {
        assertEquals(new NowPlaying.Track("Kult", "Arahja"), NowPlaying.fromOsascript(0, "Kult\tArahja\n", "").track());
        assertEquals(NowPlaying.NOTHING, NowPlaying.fromOsascript(0, "\n", "").why());
        assertEquals(NowPlaying.NO_CONSENT, NowPlaying.fromOsascript(1, "",
                "execution error: Not authorized to send Apple events to Spotify. (-1743)").why());
        assertEquals(NowPlaying.NOTHING, NowPlaying.fromOsascript(1, "", "syntax error").why());
    }

    @Test
    void theAppsAsked_spotifyAndTidalOnWindows_spotifyOnAMac_noneElsewhere() {
        assertEquals(List.of("Spotify", "Tidal"), NowPlaying.forSystem("Windows 11", clock).stream().map(NowPlaying::program).toList());
        assertEquals(List.of("Spotify"), NowPlaying.forSystem("Mac OS X", clock).stream().map(NowPlaying::program).toList());
        assertEquals(List.of(), NowPlaying.forSystem("Linux", clock));
    }

    @Test
    void eachApp_hasAVersionOfItsOwn() {
        NowPlaying tidal = new NowPlaying("Tidal", player, clock);
        assertEquals("tidal:0", tidal.version());
    }

    @Test
    void theUsualPlacesAndTheLooksList_holdTheSameSpotify() {
        // one Spotify for the program's life: a second would count from zero and, on Windows, start a second PowerShell
        List<HistorySource> usual = Platform.withoutAsking().sources();
        List<HistorySource> again = Platform.withoutAsking().sources();
        assertEquals(usual.stream().filter(NowPlaying.class::isInstance).map(System::identityHashCode).toList(),
                again.stream().filter(NowPlaying.class::isInstance).map(System::identityHashCode).toList());
    }

    @Test
    void appleScriptsAnswer_isArtistTabTitle_orNothing() {
        assertEquals(Optional.of(new NowPlaying.Track("Daria Zawiałow", "Kinga")),
                NowPlaying.fromAppleScript("Daria Zawiałow\tKinga\n"));
        assertEquals(Optional.empty(), NowPlaying.fromAppleScript("\n"));
        assertEquals(Optional.empty(), NowPlaying.fromAppleScript(""));
        assertEquals(Optional.empty(), NowPlaying.fromAppleScript("Artysta\t"));
    }
}
