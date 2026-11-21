package com.scan2play.deck;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The program's loop: what it sends, once, and what it does with the server's answers. */
class AgentTest {

    private static final Instant START = Instant.parse("2026-11-21T20:00:00Z");

    /** A history the test writes song by song. */
    private static final class FakeSource implements HistorySource {
        final List<Played> songs = new ArrayList<>();
        int version;
        boolean unreadable;

        @Override public String program() { return "VirtualDJ"; }
        @Override public boolean present() { return true; }
        @Override public String version() { return String.valueOf(version); }
        @Override public String reading() { return "2026-11-21.m3u"; }
        @Override public List<Played> songs() throws java.io.IOException {
            if (unreadable) {
                throw new java.io.IOException("no such table: history_entry");
            }
            return List.copyOf(songs);
        }

        void played(Instant at, String artist, String title) {
            songs.add(new Played(at, artist, title));
            version++;
        }
    }

    /** The server: records each call, answers as told. */
    private static final class FakeServer extends ServerClient {
        final List<List<Played>> calls = new ArrayList<>();
        ServerClient.Outcome outcome = ServerClient.Outcome.OK;
        List<String> marked = List.of();

        FakeServer() {
            super("test");
        }

        final List<String> unpaired = new ArrayList<>();
        boolean online = true;

        @Override
        boolean unpair(String server, String token) {
            if (online) {
                unpaired.add(token);
            }
            return online;
        }

        @Override
        Answer plays(String server, String token, List<Played> songs) {
            calls.add(songs);
            return new Answer(outcome, token, outcome == ServerClient.Outcome.OK ? marked : List.of());
        }
    }

    private final FakeSource source = new FakeSource();
    private final FakeServer server = new FakeServer();
    private final AtomicReference<Instant> now = new AtomicReference<>(START);
    private final AtomicReference<Agent.Status> shown = new AtomicReference<>();

    private Agent agent(Settings settings) {
        Clock clock = new Clock() {
            @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public Instant instant() { return now.get(); }
        };
        return new Agent(List.of(source), server, settings, clock, shown::set);
    }

    private static Settings paired(Path dir) throws Exception {
        Settings settings = new Settings(dir.resolve("zagrane.properties"));
        settings.paired("https://example.test", "tok");
        return settings;
    }

    @Test
    void theEarlierSet_isNotSent_aNewSongIsSentOnce(@TempDir Path dir) throws Exception {
        source.played(START.minus(Duration.ofHours(2)), "Wcześniej", "Grane dwie godziny temu");
        source.played(START.minus(Duration.ofMinutes(4)), "Tuż przed", "Ostatnia przed programem");
        Agent agent = agent(paired(dir));

        agent.tick();
        assertEquals(List.of(List.of(new Played(START.minus(Duration.ofMinutes(4)), "Tuż przed", "Ostatnia przed programem"))),
                server.calls, "only the song of the last 10 minutes");

        source.played(START.plusSeconds(200), "Sanah", "Szampan");
        server.marked = List.of("Sanah - Szampan");
        now.set(START.plusSeconds(203));
        agent.tick();
        now.set(START.plusSeconds(206));
        agent.tick();

        assertEquals(2, server.calls.size(), "nothing new, nothing sent (and the minute's call not due yet)");
        assertEquals(List.of(new Played(START.plusSeconds(200), "Sanah", "Szampan")), server.calls.get(1));
        assertEquals(List.of("Sanah - Szampan"), shown.get().marked().stream().map(Agent.Marked::song).toList());
        assertEquals(List.of("VirtualDJ (2026-11-21.m3u)"), shown.get().reading());
    }

    /**
     * A night of 100 songs is not a list of 100 in the window: the last five, each with the time it was marked, newest first, and how
     * many were marked since the start. A call that marks nothing leaves them.
     */
    @Test
    void theMarkedSongs_theLastFiveWithTheirTimes_andHowManyInAll(@TempDir Path dir) throws Exception {
        Agent agent = agent(paired(dir));
        agent.tick();
        for (int i = 1; i <= 7; i++) {
            source.played(START.plusSeconds(10L * i), "A" + i, "T" + i);
            server.marked = List.of("Wykonawca " + i + " - Tytuł " + i);
            now.set(START.plusSeconds(10L * i + 1));
            agent.tick();
        }
        server.marked = List.of();
        now.set(START.plusSeconds(200));
        agent.tick();

        assertEquals(7, shown.get().markedCount());
        assertEquals(List.of(new Agent.Marked(START.plusSeconds(71), "Wykonawca 7 - Tytuł 7"),
                new Agent.Marked(START.plusSeconds(61), "Wykonawca 6 - Tytuł 6"), new Agent.Marked(START.plusSeconds(51), "Wykonawca 5 - Tytuł 5"),
                new Agent.Marked(START.plusSeconds(41), "Wykonawca 4 - Tytuł 4"), new Agent.Marked(START.plusSeconds(31), "Wykonawca 3 - Tytuł 3")),
                shown.get().marked());
    }

    /** "Wskaż folder historii…": a folder the DJ picked is read beside the usual places, and given up for another. */
    @Test
    void aFolderThePickedDj_isReadBesideTheUsualPlaces(@TempDir Path dir) throws Exception {
        Path picked = dir.resolve("D").resolve("Muzyka").resolve("VirtualDJ").resolve("History");
        java.nio.file.Files.createDirectories(picked);
        Agent agent = agent(paired(dir));
        agent.useFolder(picked.getParent());
        java.nio.file.Files.writeString(picked.resolve("dzisiaj.m3u"), "#EXTVDJ:<lastplaytime>" + START.plusSeconds(5).getEpochSecond()
                + "</lastplaytime><artist>Sanah</artist><title>Szampan</title>\n");
        now.set(START.plusSeconds(6));
        agent.tick();

        assertTrue(shown.get().reading().contains("VirtualDJ (dzisiaj.m3u)"), "read: " + shown.get().reading());
        assertTrue(server.calls.stream().flatMap(List::stream).anyMatch(song -> song.title().equals("Szampan")), "sent");

        agent.useFolder(null);
        now.set(START.plusSeconds(9));
        agent.tick();
        assertEquals(List.of("VirtualDJ (2026-11-21.m3u)"), shown.get().reading(), "the fake source alone: the picked folder given up");
    }

    /** A history there but not readable (a new format of the program) is said in the window, not left out as if there were none. */
    @Test
    void aHistoryThatCannotBeRead_isSaid(@TempDir Path dir) throws Exception {
        source.unreadable = true;
        source.version = 1;
        Agent agent = agent(paired(dir));
        agent.tick();
        assertEquals(List.of("VirtualDJ (2026-11-21.m3u — nie da się odczytać)"), shown.get().reading());

        source.unreadable = false;
        now.set(START.plusSeconds(3));
        agent.tick();
        assertEquals(List.of("VirtualDJ (2026-11-21.m3u)"), shown.get().reading(), "read again at the next look");
    }

    @Test
    void aFailedCall_keepsTheSongsForTheNextLook(@TempDir Path dir) throws Exception {
        Agent agent = agent(paired(dir));
        agent.tick();
        source.played(START.plusSeconds(10), "Sanah", "Szampan");
        server.outcome = ServerClient.Outcome.FAILED;
        now.set(START.plusSeconds(13));
        agent.tick();
        assertEquals(1, agent.waiting().size());

        server.outcome = ServerClient.Outcome.OK;
        now.set(START.plusSeconds(16));
        agent.tick();
        assertTrue(agent.waiting().isEmpty());
    }

    @Test
    void withoutPro_theSongsAreDropped_andTheTokenStays(@TempDir Path dir) throws Exception {
        Settings settings = paired(dir);
        Agent agent = agent(settings);
        agent.tick();
        source.played(START.plusSeconds(10), "Sanah", "Szampan");
        server.outcome = ServerClient.Outcome.NO_PLAN;
        now.set(START.plusSeconds(13));
        agent.tick();

        assertTrue(agent.waiting().isEmpty(), "not sent again and again");
        assertEquals("tok", settings.token());
        assertEquals(ServerClient.Outcome.NO_PLAN, shown.get().last());
    }

    @Test
    void aComputerDisconnectedInThePanel_forgetsItsToken(@TempDir Path dir) throws Exception {
        Settings settings = paired(dir);
        Agent agent = agent(settings);
        server.outcome = ServerClient.Outcome.DISCONNECTED;

        agent.tick();

        assertNull(settings.token());
        assertNull(new Settings(dir.resolve("zagrane.properties")).token(), "forgotten on the disk too");
        assertEquals(false, shown.get().paired());
    }

    @Test
    void manySongs_goTwentyACall(@TempDir Path dir) throws Exception {
        Agent agent = agent(paired(dir));
        agent.tick();
        for (int i = 0; i < 25; i++) {
            source.played(START.plusSeconds(i), "Artysta " + i, "Piosenka " + i);
        }
        now.set(START.plusSeconds(30));
        agent.tick();
        now.set(START.plusSeconds(33));
        agent.tick();

        assertEquals(List.of(0, 20, 5), server.calls.stream().map(List::size).toList(), "the minute's call, then 20 and the rest");
    }

    /**
     * A new pairing (a new token) is asked about at once, not at the minute's call: a party without Pro is said in the window at once,
     * and an earlier "odłączono w panelu" is not left to be shown again when the DJ disconnects this computer by its own button.
     */
    @Test
    void aNewPairing_isAskedAboutAtOnce(@TempDir Path dir) throws Exception {
        Settings settings = paired(dir);
        Agent agent = agent(settings);
        server.outcome = ServerClient.Outcome.DISCONNECTED;
        agent.tick();
        assertEquals(1, server.calls.size());

        settings.paired("https://example.test", "tok2");
        server.outcome = ServerClient.Outcome.NO_PLAN;
        now.set(START.plusSeconds(10));
        agent.tick();

        assertEquals(2, server.calls.size(), "called at once, not a minute after the last call");
        assertEquals(ServerClient.Outcome.NO_PLAN, shown.get().last());
    }

    /** "Odłącz ten komputer" in the window: the server forgets the computer too (the panel's list), and so does the disk. */
    @Test
    void theWindowsOdlacz_tellsTheServer_andForgetsTheToken(@TempDir Path dir) throws Exception {
        Settings settings = paired(dir);
        Agent agent = agent(settings);

        agent.disconnect();

        assertEquals(List.of("tok"), server.unpaired);
        assertNull(new Settings(dir.resolve("zagrane.properties")).token());
        server.online = false;
        settings.paired("https://example.test", "tok2");
        agent.disconnect();
        assertNull(settings.token(), "no network: forgotten here all the same");
    }

    @Test
    void notPaired_sendsNothing(@TempDir Path dir) {
        Agent agent = agent(new Settings(dir.resolve("none.properties")));
        source.played(START, "Sanah", "Szampan");

        agent.tick();

        assertTrue(server.calls.isEmpty());
        assertEquals(false, shown.get().paired());
    }
}
