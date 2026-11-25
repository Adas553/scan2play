package com.scan2play.deck;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Windows: what the music apps play, as Windows' own media sessions say it (the system's media controls — the volume flyout, the lock
 * screen): each app's session with its state and the song's artist and title apart — Windows' documented interface, the same for
 * every app, where a window's title is each app's own text. Asked by one PowerShell for as long as one of {@link #APPS} runs, every two
 * seconds, in UTF-8 (a song's Polish letters come whole) — not one PowerShell a look, which takes a second's CPU each on a DJ's
 * laptop; it ends by itself when the apps or this program do. Spotify's window title is read beside it, for a Windows whose media
 * sessions cannot be asked (before Windows 10 1809) or a Spotify that does not report one. Only these apps' sessions are asked for
 * their song: a video in a browser, a call, another player stay unread. A PowerShell that ends at once (blocked, killed by an
 * antivirus) is started again later and later, never every 10 s for a whole night.
 */
final class WindowsMedia {

    /**
     * The apps read, as the window names them ("Tidal" as Polish writes it, "Tidala"); their processes are "&lt;name&gt;.exe" and their
     * media sessions' ids hold the name, whatever the letters' case.
     */
    static final List<String> APPS = List.of("Spotify", "Tidal");

    private static final Duration CHECK_EVERY = Duration.ofSeconds(10);
    /** An answer older than this is none — when the PowerShell has ended (an app closed): its apps are not running any more. */
    private static final Duration STALE = Duration.ofSeconds(8);
    /**
     * While the PowerShell lives, its last answer counts this long: a round slower than {@link #STALE} (a media session answering
     * late) must not read as the app closed and as a pause of the song being counted.
     */
    private static final Duration STALE_ALIVE = Duration.ofSeconds(30);
    private static final Duration SHORT_LIFE = Duration.ofSeconds(30);
    private static final Duration LONGEST_WAIT = Duration.ofMinutes(10);

    /** One app's media session: its id ("Spotify.exe", "SpotifyAB.SpotifyMusic_…!Spotify", "TIDAL.exe"), its state, its song. */
    record Session(String app, String status, String artist, String title) {

        boolean playing() {
            return "Playing".equalsIgnoreCase(status);
        }
    }

    /** One answer of the PowerShell: the apps running (lower case), every media session, Spotify's window title. */
    record Snapshot(Set<String> running, List<Session> sessions, String spotifyWindow, Instant at) {
        static final Snapshot NONE = new Snapshot(Set.of(), List.of(), "", Instant.EPOCH);
    }

    /** Read by the shutdown hook's thread too. */
    private volatile Process shell;
    private Instant startedAt = Instant.EPOCH;
    private Instant checkedAt = Instant.EPOCH;
    private Duration waitBeforeCheck = CHECK_EVERY;
    private volatile Snapshot last = Snapshot.NONE;
    private final Clock clock;

    WindowsMedia(Clock clock) {
        this.clock = clock;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            Process running = shell;
            if (running != null) {
                running.destroyForcibly();
            }
        }));
    }

    /** One app's player: running while the PowerShell's last answer, a fresh one, lists its process. */
    NowPlaying.Player player(String app) {
        return new NowPlaying.Player() {
            @Override
            public boolean running() {
                Snapshot snapshot = fresh();
                return snapshot != null && snapshot.running().contains(app.toLowerCase(Locale.ROOT));
            }

            @Override
            public NowPlaying.Heard now() {
                Snapshot snapshot = fresh();
                return snapshot == null ? NowPlaying.Heard.SILENCE : heard(snapshot, app);
            }
        };
    }

    /** The last answer while it is fresh, the PowerShell started when one of the apps runs; null when there is none. */
    private synchronized Snapshot fresh() {
        Instant now = clock.instant();
        if (shell == null || !shell.isAlive()) {
            if (shell != null) {   // ended since the last look
                waitBeforeCheck = nextWait(Duration.between(startedAt, now), waitBeforeCheck);
                shell = null;
            }
            if (Duration.between(checkedAt, now).compareTo(waitBeforeCheck) >= 0) {
                checkedAt = now;
                if (NowPlaying.processRuns(APPS.stream().map(app -> "/" + app.toLowerCase(Locale.ROOT) + ".exe").toList())) {
                    start();
                }
            }
        }
        return usable(last, now, shell != null && shell.isAlive());
    }

    /** The last answer if it still counts ({@link #STALE}, {@link #STALE_ALIVE} while the PowerShell lives); null when not. */
    static Snapshot usable(Snapshot snapshot, Instant now, boolean alive) {
        return Duration.between(snapshot.at(), now).compareTo(alive ? STALE_ALIVE : STALE) > 0 ? null : snapshot;
    }

    /** The wait before the next look for the apps after a PowerShell that lived so long: doubled from a minute while it dies young. */
    static Duration nextWait(Duration lived, Duration before) {
        if (lived.compareTo(SHORT_LIFE) >= 0) {
            return CHECK_EVERY;
        }
        Duration doubled = before.multipliedBy(2);
        return doubled.compareTo(Duration.ofMinutes(1)) < 0 ? Duration.ofMinutes(1)
                : doubled.compareTo(LONGEST_WAIT) > 0 ? LONGEST_WAIT : doubled;
    }

    /**
     * What an app plays, by its media session: playing — its artist and title; paused or stopped — nothing. An app without a session:
     * Spotify by its window's title ({@link NowPlaying#fromWindow}: none at all is the window closed to the tray), another nothing.
     */
    static NowPlaying.Heard heard(Snapshot snapshot, String app) {
        String name = app.toLowerCase(Locale.ROOT);
        List<Session> sessions = snapshot.sessions().stream().filter(s -> s.app().toLowerCase(Locale.ROOT).contains(name)).toList();
        if (sessions.isEmpty()) {
            return name.equals("spotify") ? NowPlaying.fromWindow(snapshot.spotifyWindow()) : NowPlaying.Heard.SILENCE;
        }
        return NowPlaying.Heard.of(sessions.stream().filter(Session::playing).filter(s -> !s.title().isBlank())
                // an ad: Spotify's "Advertisement" (its artist "Spotify" or none)
                .filter(s -> !s.title().strip().equalsIgnoreCase("Advertisement") && !s.artist().strip().equalsIgnoreCase("Spotify"))
                .map(s -> new NowPlaying.Track(s.artist().strip(), s.title().strip())).findFirst());
    }

    /**
     * One answer of the PowerShell, its lines: "P=spotify,tidal", "S=&lt;app&gt;\t&lt;state&gt;\t&lt;artist&gt;\t&lt;title&gt;" per
     * session, "W=&lt;Spotify's window title&gt;" (the "E=" that ends it not among them). Any other line left out; the first line may
     * start with the byte order mark PowerShell's UTF-8 writes.
     */
    static Snapshot parse(List<String> lines, Instant at) {
        Set<String> running = new HashSet<>();
        List<Session> sessions = new ArrayList<>();
        String window = "";
        for (String raw : lines) {
            String line = raw.startsWith("\uFEFF") ? raw.substring(1) : raw;
            if (line.startsWith("P=")) {
                for (String app : line.substring(2).split(",")) {
                    if (!app.isBlank()) {
                        running.add(app.strip().toLowerCase(Locale.ROOT));
                    }
                }
            } else if (line.startsWith("S=")) {
                String[] parts = line.substring(2).split("\t", -1);
                if (parts.length == 4) {
                    sessions.add(new Session(parts[0], parts[1], parts[2], parts[3]));
                }
            } else if (line.startsWith("W=")) {
                window = line.substring(2);
            }
        }
        return new Snapshot(Set.copyOf(running), List.copyOf(sessions), window, at);
    }

    /**
     * The PowerShell's script: Windows PowerShell 5.1 (powershell.exe — WinRT's types are reachable there, not in PowerShell 7),
     * given as -EncodedCommand (no quoting of its own quotes and backticks). Media sessions that cannot be asked leave only the
     * processes and the window's title.
     */
    static String script(long parent) {
        return script(parent, APPS);
    }

    /** The script for these apps (a test on Windows asks it about PowerShell itself). */
    static String script(long parent, List<String> apps) {
        return """
                $ErrorActionPreference = 'SilentlyContinue'
                [Console]::OutputEncoding = [Text.Encoding]::UTF8
                $parent = %d
                $apps = %s
                $only = '%s'
                $manager = $null
                function Clean($text) { if ($null -eq $text) { '' } else { [string]$text -replace "[`t`r`n]", ' ' } }
                try {
                    Add-Type -AssemblyName System.Runtime.WindowsRuntime
                    $null = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager, Windows.Media.Control, ContentType = WindowsRuntime]
                    $asTask = [System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object {
                        $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1'
                    } | Select-Object -First 1
                    function Await($operation, [Type]$type) {
                        $task = $asTask.MakeGenericMethod($type).Invoke($null, @($operation))
                        if ($task.Wait(3000)) { $task.Result } else { $null }
                    }
                    $manager = Await ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager]::RequestAsync()) ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager])
                } catch { $manager = $null }
                while ($true) {
                    if (-not (Get-Process -Id $parent)) { exit }
                    $running = @(Get-Process -Name $apps)
                    if ($running.Count -eq 0) { exit }
                    $out = New-Object System.Text.StringBuilder
                    [void]$out.AppendLine('P=' + ((@($running | ForEach-Object { $_.ProcessName.ToLower() }) | Sort-Object -Unique) -join ','))
                    if ($manager) {
                        foreach ($session in $manager.GetSessions()) {
                            if ([string]$session.SourceAppUserModelId -notmatch $only) { continue }
                            $status = $session.GetPlaybackInfo().PlaybackStatus
                            $media = Await ($session.TryGetMediaPropertiesAsync()) ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionMediaProperties])
                            [void]$out.AppendLine('S=' + (Clean $session.SourceAppUserModelId) + "`t" + (Clean $status) + "`t" + (Clean $media.Artist) + "`t" + (Clean $media.Title))
                        }
                    }
                    $window = $running | Where-Object { $_.ProcessName -eq 'Spotify' -and $_.MainWindowTitle } | Select-Object -First 1
                    [void]$out.AppendLine('W=' + (Clean $window.MainWindowTitle))
                    [void]$out.Append('E=')
                    [Console]::Out.WriteLine($out.ToString())
                    [Console]::Out.Flush()
                    Start-Sleep -Seconds 2
                }
                """.formatted(parent, String.join(",", apps.stream().map(app -> "'" + app + "'").toList()),
                String.join("|", apps.stream().map(app -> app.toLowerCase(Locale.ROOT)).toList()));
    }

    /** The script as PowerShell's -EncodedCommand takes it: Base64 of its UTF-16LE. */
    static String encoded(String script) {
        return Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
    }

    private void start() {
        try {
            Process started = new ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-EncodedCommand",
                    encoded(script(ProcessHandle.current().pid()))).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            started.getOutputStream().close();
            shell = started;
            startedAt = clock.instant();
            Thread reader = new Thread(() -> listen(started), "media-sessions");
            reader.setDaemon(true);
            reader.start();
        } catch (IOException e) {
            shell = null;   // no PowerShell (blocked by the IT): the apps are not read, the rest works
        }
    }

    private void listen(Process process) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            List<String> lines = new ArrayList<>();
            for (String line; (line = reader.readLine()) != null; ) {
                if (line.startsWith("E=") || line.startsWith("\uFEFFE=")) {
                    last = parse(lines, clock.instant());
                    lines.clear();
                } else if (lines.size() < 100) {
                    lines.add(line);
                }
            }
        } catch (IOException e) {
            // the shell ended: started again at the next check while an app runs
        }
    }
}
