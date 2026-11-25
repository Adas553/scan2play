package com.scan2play.deck;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
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
 * Spotify's desktop app as a history (docs/ideas.md item 20): Spotify writes none and its Web API is closed to an app like ours, so
 * the song playing now is asked of the app itself at each look — on Windows its window's title ("Artist - Title" while it plays, "Spotify
 * Premium" paused), on macOS by AppleScript — and a song heard for {@link #PLAYED_AFTER} becomes a played one, its start the time it
 * was first heard. A short pause stops the count, it goes on when the same song plays again; another song, or the same one after a
 * pause over {@link #LONG_PAUSE}, starts it anew. The same song again with no other counted in between is one play: a DJ who clicked
 * another song by mistake for a few seconds and went back.
 */
final class SpotifyNowPlaying implements HistorySource {

    /** How long a song must play to count: a DJ skimming through songs in Spotify plays none of them. */
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

    /** The window's words for Spotify running and no song heard. */
    static final String NOTHING = "nic nie gra";
    static final String HIDDEN = "okno schowane — nie widać, co gra";
    static final String NO_CONSENT = "brak zgody macOS — nie widać, co gra";

    /** What Spotify plays now. */
    record Track(String artist, String title) {
    }

    /** What Spotify plays now: a song, or none and the window's words why ({@link #NOTHING}, {@link #HIDDEN}, {@link #NO_CONSENT}). */
    record Heard(Track track, String why) {
        static final Heard SILENCE = new Heard(null, NOTHING);

        static Heard of(Optional<Track> track) {
            return track.map(t -> new Heard(t, "")).orElse(SILENCE);
        }
    }

    /** Spotify on this computer: whether it runs, and what plays now. */
    interface Player {
        boolean running();

        Heard now();
    }

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

    SpotifyNowPlaying(Player player, Clock clock) {
        this.player = player;
        this.clock = clock;
    }

    /** Spotify of this computer's system, or none on a system it is not asked on. */
    static Optional<HistorySource> onThisComputer() {
        return Optional.ofNullable(playerFor(System.getProperty("os.name", "")))
                .map(player -> new SpotifyNowPlaying(player, Clock.systemDefaultZone()));
    }

    /** The way Spotify is asked on a system ("Windows 11", "Mac OS X"); none elsewhere. Starts nothing yet. */
    static Player playerFor(String osName) {
        String os = osName.toLowerCase(Locale.ROOT);
        return os.contains("win") ? new WindowsSpotify() : os.contains("mac") ? new MacSpotify() : null;
    }

    @Override
    public String program() {
        return "Spotify";
    }

    @Override
    public boolean present() {
        return true;
    }

    /** Asks Spotify what plays now (each look); a new song played changes it. Empty while Spotify is not running. */
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
        return running ? "spotify:" + playedEver : "";
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

    /** A line of the Windows PowerShell: "T=" and the window's title; the first line may start with UTF-8's BOM. None for another line. */
    static Optional<String> titleOf(String line) {
        String text = line.startsWith("\uFEFF") ? line.substring(1) : line;
        return text.startsWith("T=") ? Optional.of(text.substring(2)) : Optional.empty();
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
    private static boolean processRuns(String... endings) {
        return ProcessHandle.allProcesses().anyMatch(process -> process.info().command()
                .map(command -> command.toLowerCase(Locale.ROOT).replace('\\', '/'))
                .filter(command -> List.of(endings).stream().anyMatch(command::endsWith))
                .isPresent());
    }

    /**
     * Windows: one PowerShell for as long as Spotify runs, writing the title of Spotify's window every two seconds in UTF-8 (a song's
     * Polish letters come whole; tasklist answers in the console's code page) — not one PowerShell a look, which takes a second's CPU
     * each on a DJ's laptop. It ends by itself when Spotify or this program does. Spotify closed to the tray has no window title:
     * nothing is heard then, and the window says so ({@link #HIDDEN}). A PowerShell that ends at once (blocked, killed by an
     * antivirus) is started again later and later, never every 10 s for a whole night.
     */
    static final class WindowsSpotify implements Player {

        private static final Duration CHECK_EVERY = Duration.ofSeconds(10);
        private static final Duration STALE = Duration.ofSeconds(8);
        private static final Duration SHORT_LIFE = Duration.ofSeconds(30);
        private static final Duration LONGEST_WAIT = Duration.ofMinutes(10);

        /** Read by the shutdown hook's thread too. */
        private volatile Process shell;
        private Instant startedAt = Instant.EPOCH;
        private Instant checkedAt = Instant.EPOCH;
        private Duration waitBeforeCheck = CHECK_EVERY;
        private volatile String title = "";
        private volatile Instant heardAt = Instant.EPOCH;

        WindowsSpotify() {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                Process last = shell;
                if (last != null) {
                    last.destroyForcibly();
                }
            }));
        }

        @Override
        public boolean running() {
            if (shell != null && shell.isAlive()) {
                return true;
            }
            Instant now = Instant.now();
            if (shell != null) {   // ended since the last look
                waitBeforeCheck = nextWait(Duration.between(startedAt, now), waitBeforeCheck);
                shell = null;
            }
            if (Duration.between(checkedAt, now).compareTo(waitBeforeCheck) < 0) {
                return false;
            }
            checkedAt = now;
            if (!processRuns("/spotify.exe")) {
                return false;
            }
            start();
            return shell != null && shell.isAlive();
        }

        @Override
        public Heard now() {
            return Duration.between(heardAt, Instant.now()).compareTo(STALE) > 0 ? Heard.SILENCE : fromWindow(title);
        }

        /** The wait before the next look for Spotify after a PowerShell that lived so long: doubled from a minute while it dies young. */
        static Duration nextWait(Duration lived, Duration before) {
            if (lived.compareTo(SHORT_LIFE) >= 0) {
                return CHECK_EVERY;
            }
            Duration doubled = before.multipliedBy(2);
            return doubled.compareTo(Duration.ofMinutes(1)) < 0 ? Duration.ofMinutes(1)
                    : doubled.compareTo(LONGEST_WAIT) > 0 ? LONGEST_WAIT : doubled;
        }

        private void start() {
            String script = "$ErrorActionPreference='SilentlyContinue';[Console]::OutputEncoding=[Text.Encoding]::UTF8;"
                    + "while($true){"
                    + "if(-not(Get-Process -Id " + ProcessHandle.current().pid() + ")){exit};"
                    + "$all=@(Get-Process -Name Spotify);if($all.Count -eq 0){exit};"
                    + "$w=$all|Where-Object{$_.MainWindowTitle}|Select-Object -First 1;"
                    + "[Console]::Out.WriteLine('T='+$w.MainWindowTitle);[Console]::Out.Flush();"
                    + "Start-Sleep -Seconds 2}";
            try {
                Process started = new ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command", script)
                        .redirectError(ProcessBuilder.Redirect.DISCARD).start();
                started.getOutputStream().close();
                shell = started;
                startedAt = Instant.now();
                Thread reader = new Thread(() -> listen(started), "spotify");
                reader.setDaemon(true);
                reader.start();
            } catch (IOException e) {
                shell = null;   // no PowerShell (blocked by the IT): Spotify is not read, the rest works
            }
        }

        private void listen(Process process) {
            try (BufferedReader lines = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                for (String line; (line = lines.readLine()) != null; ) {
                    titleOf(line).ifPresent(heard -> {
                        title = heard;
                        heardAt = Instant.now();
                    });
                }
            } catch (IOException e) {
                // the shell ended: asked again at the next check
            }
        }
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
                runs = processRuns("/spotify.app/contents/macos/spotify");
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
