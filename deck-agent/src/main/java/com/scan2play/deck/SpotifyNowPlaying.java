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
 * was first heard. A pause stops the count, it goes on when the same song plays again; another song starts it anew. The same song
 * played again straight after is not a second play (a pause long enough to read "Spotify Premium" is not a new song).
 */
final class SpotifyNowPlaying implements HistorySource {

    /** How long a song must play to count: a DJ skimming through songs in Spotify plays none of them. */
    static final Duration PLAYED_AFTER = Duration.ofSeconds(30);
    /** A look later than this after the previous one (a computer asleep) adds only this much: the time between was not heard. */
    static final Duration LONGEST_GAP = Duration.ofSeconds(10);
    /** The songs kept for the agent: tonight's last ones; the agent has its own "seen". */
    static final int KEPT = 200;

    /** What Spotify plays now. */
    record Track(String artist, String title) {
    }

    /** Spotify on this computer: whether it runs, and the song playing now (empty: paused, an ad, unknown). */
    interface Player {
        boolean running();

        Optional<Track> now();
    }

    private final Player player;
    private final Clock clock;
    private final List<Played> played = new ArrayList<>();
    private int playedEver;
    private boolean running;
    private Track current;
    private Track counting;
    private Instant countingSince;
    private Duration heard = Duration.ZERO;
    private boolean countedOnce;
    private Instant lastHeard;
    private Track lastPlayed;

    SpotifyNowPlaying(Player player, Clock clock) {
        this.player = player;
        this.clock = clock;
    }

    /** Spotify of this computer's system, or none on a system it is not asked on. */
    static Optional<HistorySource> onThisComputer() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        Player player = os.contains("win") ? new WindowsSpotify() : os.contains("mac") ? new MacSpotify() : null;
        return Optional.ofNullable(player).map(p -> new SpotifyNowPlaying(p, Clock.systemDefaultZone()));
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
        current = running ? player.now().orElse(null) : null;
        Instant now = clock.instant();
        if (current == null) {
            lastHeard = null;   // paused: the time until the next look was not heard
        } else {
            if (!current.equals(counting)) {
                counting = current;
                countingSince = now;
                heard = Duration.ZERO;
                countedOnce = false;
            } else if (lastHeard != null) {
                Duration since = Duration.between(lastHeard, now);
                heard = heard.plus(since.compareTo(LONGEST_GAP) > 0 ? LONGEST_GAP : since.isNegative() ? Duration.ZERO : since);
            }
            lastHeard = now;
            if (!countedOnce && heard.compareTo(PLAYED_AFTER) >= 0) {
                countedOnce = true;
                if (!counting.equals(lastPlayed)) {
                    lastPlayed = counting;
                    played.add(new Played(countingSince, counting.artist(), counting.title()));
                    playedEver++;
                    if (played.size() > KEPT) {
                        played.removeFirst();
                    }
                }
            }
        }
        return running ? "spotify:" + playedEver : "";
    }

    @Override
    public synchronized String reading() {
        return current == null ? "nic nie gra" : "gra: " + (current.artist().isEmpty() ? current.title() : current.artist() + " - " + current.title());
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
     * nothing is heard then.
     */
    static final class WindowsSpotify implements Player {

        private static final Duration CHECK_EVERY = Duration.ofSeconds(10);
        private static final Duration STALE = Duration.ofSeconds(8);

        private Process shell;
        private Instant checkedAt = Instant.EPOCH;
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
            if (Duration.between(checkedAt, now).compareTo(CHECK_EVERY) < 0) {
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
        public Optional<Track> now() {
            return Duration.between(heardAt, Instant.now()).compareTo(STALE) > 0 ? Optional.empty() : fromWindowTitle(title);
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
                    line = line.replace("﻿", "");
                    if (line.startsWith("T=")) {
                        title = line.substring(2);
                        heardAt = Instant.now();
                    }
                }
            } catch (IOException e) {
                // the shell ended: asked again at the next check
            }
        }
    }

    /**
     * macOS: AppleScript asked at each look while Spotify runs (osascript, a few hundredths of a second). The first time macOS asks the
     * DJ whether "Scan2Play Zagrane" may control Spotify; a "Nie pozwalaj" leaves Spotify unread (System Settings → Privacy → Automation).
     * An ad is no song: its track's address starts "spotify:ad".
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

        @Override
        public boolean running() {
            return processRuns("/spotify.app/contents/macos/spotify");
        }

        @Override
        public Optional<Track> now() {
            try {
                Process script = new ProcessBuilder("osascript", "-e", SCRIPT).redirectError(ProcessBuilder.Redirect.DISCARD).start();
                script.getOutputStream().close();
                if (!script.waitFor(5, TimeUnit.SECONDS)) {
                    script.destroyForcibly();
                    return Optional.empty();
                }
                return fromAppleScript(new String(script.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
            } catch (IOException e) {
                return Optional.empty();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            }
        }
    }
}
