package com.scan2play.deck;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Windows' media sessions as the PowerShell writes them, and what each app plays by them. */
class WindowsMediaTest {

    private static final Instant AT = Instant.parse("2026-11-25T20:00:00Z");

    private static WindowsMedia.Snapshot snapshot(String... lines) {
        return WindowsMedia.parse(List.of(lines), AT);
    }

    private static NowPlaying.Track song(String artist, String title) {
        return new NowPlaying.Track(artist, title);
    }

    @Test
    void theAnswer_isTheAppsRunning_theSessions_andSpotifysWindow() {
        WindowsMedia.Snapshot read = snapshot("\uFEFFP=spotify,tidal",
                "S=Spotify.exe\tPlaying\tSanah\tSzampan",
                "S=TIDAL.exe\tPaused\tKult\tArahja",
                "S=broken line",
                "W=Sanah - Szampan",
                "Get-Process : nie znaleziono");
        assertEquals(Set.of("spotify", "tidal"), read.running(), "the first line's byte order mark left out");
        assertEquals(List.of(new WindowsMedia.Session("Spotify.exe", "Playing", "Sanah", "Szampan"),
                new WindowsMedia.Session("TIDAL.exe", "Paused", "Kult", "Arahja")), read.sessions());
        assertEquals("Sanah - Szampan", read.spotifyWindow());
        assertEquals(AT, read.at());
    }

    @Test
    void anAppPlaying_isItsSessionsSong_pausedNothing() {
        WindowsMedia.Snapshot read = snapshot("P=spotify,tidal",
                "S=Spotify.exe\tPlaying\tDawid Podsiadło\tMałomiasteczkowy",
                "S=TIDAL.exe\tPaused\tKult\tArahja",
                "W=Spotify Premium");
        assertEquals(song("Dawid Podsiadło", "Małomiasteczkowy"), WindowsMedia.heard(read, "Spotify").track(),
                "the session, not the window's title");
        assertNull(WindowsMedia.heard(read, "Tidal").track());
        assertEquals(NowPlaying.NOTHING, WindowsMedia.heard(read, "Tidal").why());
    }

    @Test
    void theStoresSpotify_isFoundByItsSessionsName() {
        WindowsMedia.Snapshot read = snapshot("P=spotify", "S=SpotifyAB.SpotifyMusic_zpdnekdrzrea0!Spotify\tPlaying\tKult\tArahja");
        assertEquals(song("Kult", "Arahja"), WindowsMedia.heard(read, "Spotify").track());
    }

    @Test
    void spotifyHiddenInTheTray_isStillHeardByItsSession() {
        WindowsMedia.Snapshot read = snapshot("P=spotify", "S=Spotify.exe\tPlaying\tSanah\tSzampan", "W=");
        assertEquals(song("Sanah", "Szampan"), WindowsMedia.heard(read, "Spotify").track());
    }

    @Test
    void spotifyWithoutASession_isReadByItsWindow_tidalIsNot() {
        WindowsMedia.Snapshot read = snapshot("P=spotify,tidal", "W=Sanah - Szampan");
        assertEquals(song("Sanah", "Szampan"), WindowsMedia.heard(read, "Spotify").track());
        assertNull(WindowsMedia.heard(read, "Tidal").track());
        assertEquals(NowPlaying.HIDDEN, WindowsMedia.heard(snapshot("P=spotify", "W="), "Spotify").why(),
                "no session and no window title: closed to the tray");
        WindowsMedia.Snapshot paused = snapshot("P=spotify", "S=Spotify.exe\tPaused\tSanah\tSzampan", "W=Sanah - Szampan");
        assertNull(WindowsMedia.heard(paused, "Spotify").track(), "a session paused: its window's title not asked");
    }

    @Test
    void anAd_orASessionWithoutATitle_isNoSong_norAnotherAppsSession() {
        assertNull(WindowsMedia.heard(snapshot("S=Spotify.exe\tPlaying\t\tAdvertisement"), "Spotify").track());
        assertNull(WindowsMedia.heard(snapshot("S=Spotify.exe\tPlaying\tSpotify\tAdvertisement"), "Spotify").track());
        assertNull(WindowsMedia.heard(snapshot("S=TIDAL.exe\tPlaying\tKult\t"), "Tidal").track());
        assertNull(WindowsMedia.heard(snapshot("S=chrome.exe\tPlaying\tKult\tArahja"), "Tidal").track(),
                "a browser's session is not the app's");
    }

    @Test
    void aPowerShellDyingYoung_isStartedAgainLaterAndLater() {
        Duration wait = Duration.ofSeconds(10);
        List<Duration> waits = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            wait = WindowsMedia.nextWait(Duration.ofSeconds(1), wait);
            waits.add(wait);
        }
        assertEquals(List.of(Duration.ofMinutes(1), Duration.ofMinutes(2), Duration.ofMinutes(4), Duration.ofMinutes(8),
                Duration.ofMinutes(10), Duration.ofMinutes(10)), waits);
        assertEquals(Duration.ofSeconds(10), WindowsMedia.nextWait(Duration.ofHours(1), Duration.ofMinutes(10)),
                "one that lived (the apps closed): looked for every 10 s again");
    }

    @Test
    void theScript_asksBothApps_endsWithThisProgram_andGoesEncoded() {
        String script = WindowsMedia.script(4242);
        assertTrue(script.contains("$parent = 4242"), script);
        assertTrue(script.contains("$apps = 'Spotify','Tidal'"), script);
        // only the apps' sessions are asked for their song: a browser's video, a call stay unread (the privacy policy)
        int filter = script.indexOf("-notmatch $only) { continue }");
        assertTrue(script.contains("$only = 'spotify|tidal'") && filter > 0 && filter < script.indexOf("TryGetMediaPropertiesAsync"), script);
        assertEquals(script, new String(Base64.getDecoder().decode(WindowsMedia.encoded(script)), StandardCharsets.UTF_16LE));
    }

    @Test
    void anAd_byItsArtistSpotify_isNoSong() {
        assertNull(WindowsMedia.heard(snapshot("S=Spotify.exe\tPlaying\tSpotify\tPosłuchaj bez reklam"), "Spotify").track());
    }

    @Test
    void tidalsSession_isFoundByItsNameAnywhereInItsId() {
        assertEquals(song("Kult", "Arahja"),
                WindowsMedia.heard(snapshot("S=com.squirrel.TIDAL.TIDAL\tPlaying\tKult\tArahja"), "Tidal").track());
    }

    @Test
    void anOldAnswer_countsLongerWhileThePowerShellLives() {
        WindowsMedia.Snapshot answer = snapshot("P=spotify");
        assertEquals(answer, WindowsMedia.usable(answer, AT.plusSeconds(8), false));
        assertNull(WindowsMedia.usable(answer, AT.plusSeconds(9), false), "the PowerShell ended: the apps closed");
        assertEquals(answer, WindowsMedia.usable(answer, AT.plusSeconds(25), true), "a slow round is not the app closed");
        assertNull(WindowsMedia.usable(answer, AT.plusSeconds(31), true), "a PowerShell that hangs: nothing heard");
    }

    /** The real script in Windows PowerShell 5.1 (the package job on Windows runs it): asked about PowerShell itself, it answers. */
    @Test
    @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS)
    void theScript_runsInWindowsPowerShell() throws Exception {
        Process shell = new ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-EncodedCommand",
                WindowsMedia.encoded(WindowsMedia.script(ProcessHandle.current().pid(), List.of("powershell"))))
                .redirectErrorStream(true).start();
        try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(shell.getInputStream(), StandardCharsets.UTF_8))) {
            List<String> lines = new ArrayList<>();
            for (String line; (line = reader.readLine()) != null && !line.replace("\uFEFF", "").startsWith("E="); ) {
                lines.add(line);
            }
            assertTrue(WindowsMedia.parse(lines, AT).running().contains("powershell"), String.join("\n", lines));
        } finally {
            shell.destroyForcibly();
        }
    }
}
