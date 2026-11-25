package com.scan2play.deck;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * A music app as a history (docs/ideas.md item 20): Spotify and TIDAL write none and their Web APIs are closed to an app like ours, so
 * the song playing now is asked of the app itself at each look — on Windows by Windows' media sessions ({@link WindowsMedia}; Spotify
 * without one by its window's title), on macOS Spotify by AppleScript — and a song heard for {@link #PLAYED_AFTER} becomes a played
 * one, its start the time it was first heard. A short pause stops the count, it goes on when the same song plays again; another
 * song, or the same one after a pause over {@link #LONG_PAUSE}, starts it anew. The same song again with no other counted in between is one play: a DJ who clicked
 * another song by mistake for a few seconds and went back.
 */
final class NowPlaying implements HistorySource {

    /** How long a song must play to count: a DJ skimming through songs in the app plays none of them. */
    static final Duration PLAYED_AFTER = Duration.ofSeconds(30);
    /** A look later than this after the previous one (a computer asleep) adds only this much: the time between was not heard. */
    static final Duration LONGEST_GAP = Duration.ofSeconds(10);
    /**
     * A pause after which the same song is a new one, from the time it plays again: the server marks no request asked more than 2
     * minutes after a song's start (DeckHistoryService.SLACK_CLOCK_KNOWN), so a song paused for the set of another program and played
     * on must not keep its old start.
     */
    static final Duration LONG_PAUSE = Duration.ofMinutes(2);
    /** The songs kept for the agent: tonight's last ones; the agent has its own "seen". */
    static final int KEPT = 200;

    /** The window's words for the app running and no song heard. */
    static final String NOTHING = "nic nie gra";
    static final String HIDDEN = "okno schowane — nie widać, co gra";
    static final String NO_CONSENT = "brak zgody macOS — nie widać, co gra";

    /** What the app plays now. */
    record Track(String artist, String title) {
    }

    /** What the app plays now: a song, or none and the window's words why ({@link #NOTHING}, {@link #HIDDEN}, {@link #NO_CONSENT}). */
    record Heard(Track track, String why) {
        static final Heard SILENCE = new Heard(null, NOTHING);

        static Heard of(Optional<Track> track) {
            return track.map(t -> new Heard(t, "")).orElse(SILENCE);
        }
    }

    /** The app on this computer: whether it runs, and what plays now. */
    interface Player {
        boolean running();

        Heard now();
    }

    private final String program;
    private final Player player;
    private final Clock clock;
    private final List<Played> played = new ArrayList<>();
    private int playedEver;
    private boolean running;
    private Heard current = Heard.SILENCE;
    private Track counting;
    private Instant countingSince;
    private Duration heard = Duration.ZERO;
    /** The last look that heard the song counted; null while paused. */
    private Instant lastHeard;
    /** The last look that heard any song, kept through a pause: how long the pause is. */
    private Instant heardBefore;
    private Track lastPlayed;

    NowPlaying(String program, Player player, Clock clock) {
        this.program = program;
        this.player = player;
        this.clock = clock;
    }

    /** The music apps of this computer's system. */
    static List<NowPlaying> onThisComputer() {
        return forSystem(System.getProperty("os.name", ""), Clock.systemDefaultZone());
    }

    /**
     * The music apps asked on a system ("Windows 11", "Mac OS X"): on Windows {@link WindowsMedia#APPS}, sharing one
     * {@link WindowsMedia}; on macOS Spotify (TIDAL has no AppleScript, and macOS' own "Now Playing" is closed to other programs —
     * TIDAL inside VirtualDJ or Serato is in their history); none elsewhere. Starts nothing yet.
     */
    static List<NowPlaying> forSystem(String osName, Clock clock) {
        List<String> apps = appsFor(osName);
        if (apps.equals(WindowsMedia.APPS)) {
            WindowsMedia media = new WindowsMedia(clock);
            return apps.stream().map(app -> new NowPlaying(app, media.player(app), clock)).toList();
        }
        return apps.isEmpty() ? List.of() : List.of(new NowPlaying("Spotify", new MacSpotify(), clock));
    }

    /** The music apps' names read on a system, for the window's words too: on a Mac no "Tidal" the program does not read there. */
    static List<String> appsFor(String osName) {
        String os = osName.toLowerCase(Locale.ROOT);
        return os.contains("win") ? WindowsMedia.APPS : os.contains("mac") ? List.of("Spotify") : List.of();
    }

    /** The music apps' names read on this computer. */
    static List<String> appsOnThisComputer() {
        return appsFor(System.getProperty("os.name", ""));
    }

    @Override
    public String program() {
        return program;
    }

    @Override
    public boolean present() {
        return true;
    }

    /** Asks the app what plays now (each look); a new song played changes it. Empty while the app is not running. */
    @Override
    public synchronized String version() {
        running = player.running();
        current = running ? player.now() : Heard.SILENCE;
        Instant now = clock.instant();
        Track track = current.track();
        if (track == null) {
            lastHeard = null;   // paused: the time until the next look was not heard
        } else {
            boolean longPause = lastHeard == null && heardBefore != null
                    && Duration.between(heardBefore, now).compareTo(LONG_PAUSE) > 0;
            if (longPause) {
                lastPlayed = null;   // played on after the set of another program: a play of its own
            }
            if (!track.equals(counting) || longPause) {
                counting = track;
                countingSince = now;
                heard = Duration.ZERO;
            } else if (lastHeard != null) {
                Duration since = Duration.between(lastHeard, now);
                heard = heard.plus(since.compareTo(LONGEST_GAP) > 0 ? LONGEST_GAP : since.isNegative() ? Duration.ZERO : since);
            }
            lastHeard = now;
            heardBefore = now;
            if (heard.compareTo(PLAYED_AFTER) >= 0 && !counting.equals(lastPlayed)) {
                lastPlayed = counting;
                played.add(new Played(countingSince, counting.artist(), counting.title()));
                playedEver++;
                if (played.size() > KEPT) {
                    played.removeFirst();
                }
            }
        }
        return running ? program.toLowerCase(Locale.ROOT) + ":" + playedEver : "";
    }

    @Override
    public synchronized String reading() {
        Track track = current.track();
        return track == null ? current.why() : "gra: " + (track.artist().isEmpty() ? track.title() : track.artist() + " - " + track.title());
    }

    @Override
    public synchronized List<Played> songs() {
        return List.copyOf(played);
    }

    /**
     * The song of a Spotify window's title: "Artist - Title" (the title may have " - " of its own: "Song - Remastered 2011"); none for
     * Spotify paused ("Spotify Premium", "Spotify Free", "Spotify") or an ad ("Advertisement", "Spotify - Advertisement").
     */
    static Optional<Track> fromWindowTitle(String windowTitle) {
        String text = windowTitle == null ? "" : windowTitle.strip();
        int dash = text.indexOf(" - ");
        if (dash <= 0) {
            return Optional.empty();
        }
        String artist = text.substring(0, dash).strip();
        String title = text.substring(dash + 3).strip();
        if (title.isEmpty() || artist.equalsIgnoreCase("Spotify") || artist.equalsIgnoreCase("Advertisement")) {
            return Optional.empty();
        }
        return Optional.of(new Track(artist, title));
    }

    /** A Spotify window's title as heard: a song, paused ({@link #NOTHING}), or none at all — the window closed to the tray ({@link #HIDDEN}). */
    static Heard fromWindow(String windowTitle) {
        return windowTitle == null || windowTitle.isBlank() ? new Heard(null, HIDDEN) : Heard.of(fromWindowTitle(windowTitle));
    }

    /**
     * osascript's answer: the song, or nothing; "-1743" (Not authorized to send Apple events) is the DJ's "Nie pozwalaj", said in the
     * window — without it Spotify would only seem silent.
     */
    static Heard fromOsascript(int exit, String out, String err) {
        if (exit != 0) {
            return err != null && err.contains("-1743") ? new Heard(null, NO_CONSENT) : Heard.SILENCE;
        }
        return Heard.of(fromAppleScript(out));
    }

    /** AppleScript's answer: "artist&lt;tab&gt;title", or nothing (paused, an ad, not running). */
    static Optional<Track> fromAppleScript(String answer) {
        String text = answer == null ? "" : answer.strip();
        int tab = text.indexOf('\t');
        if (tab < 0) {
            return Optional.empty();
        }
        String title = text.substring(tab + 1).strip();
        return title.isEmpty() ? Optional.empty() : Optional.of(new Track(text.substring(0, tab).strip(), title));
    }

    /** Whether a process of this computer is one of these programs (its file's path ends so, whatever the letters' case). */
    static boolean processRuns(List<String> endings) {
        return ProcessHandle.allProcesses().anyMatch(process -> process.info().command()
                .map(command -> command.toLowerCase(Locale.ROOT).replace('\\', '/'))
                .filter(command -> endings.stream().anyMatch(command::endsWith))
                .isPresent());
    }

        /**
     * macOS: AppleScript asked at each look while Spotify runs (osascript, a few hundredths of a second). The first time macOS asks the
     * DJ whether "Scan2Play Zagrane" may control Spotify; a "Nie pozwalaj" leaves Spotify unread (System Settings → Privacy → Automation),
     * said in the window ({@link #NO_CONSENT}). An ad is no song: its track's address starts "spotify:ad". Whether Spotify runs is
     * looked for every 10 s, as on Windows: the list of all processes at every look would cost more than the answer.
     */
    static final class MacSpotify implements Player {

        private static final String SCRIPT = """
                if application "Spotify" is running then
                    tell application "Spotify"
                        if player state is playing then
                            set t to current track
                            if (spotify url of t) starts with "spotify:ad" then return ""
                            return (artist of t) & tab & (name of t)
                        end if
                    end tell
                end if
                return ""
                """;

        private static final Duration CHECK_EVERY = Duration.ofSeconds(10);

        private Instant checkedAt = Instant.EPOCH;
        private boolean runs;

        @Override
        public boolean running() {
            Instant now = Instant.now();
            if (Duration.between(checkedAt, now).compareTo(CHECK_EVERY) >= 0) {
                checkedAt = now;
                runs = processRuns(List.of("/spotify.app/contents/macos/spotify"));
            }
            return runs;
        }

        @Override
        public Heard now() {
            try {
                Process script = new ProcessBuilder("osascript", "-e", SCRIPT).start();
                script.getOutputStream().close();
                if (!script.waitFor(5, TimeUnit.SECONDS)) {
                    script.destroyForcibly();
                    return Heard.SILENCE;
                }
                // both read after the end: a line or two, never enough to fill a pipe and block osascript
                return fromOsascript(script.exitValue(), new String(script.getInputStream().readAllBytes(), StandardCharsets.UTF_8),
                        new String(script.getErrorStream().readAllBytes(), StandardCharsets.UTF_8));
            } catch (IOException e) {
                return Heard.SILENCE;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Heard.SILENCE;
            }
        }
    }
}
