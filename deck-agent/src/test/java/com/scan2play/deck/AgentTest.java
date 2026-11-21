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

        @Override public String program() { return "VirtualDJ"; }
        @Override public boolean present() { return true; }
        @Override public String version() { return String.valueOf(version); }
        @Override public String reading() { return "2026-11-21.m3u"; }
        @Override public List<Played> songs() { return List.copyOf(songs); }

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
        assertEquals(List.of("Sanah - Szampan"), shown.get().marked());
        assertEquals(List.of("VirtualDJ (2026-11-21.m3u)"), shown.get().reading());
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

    @Test
    void notPaired_sendsNothing(@TempDir Path dir) {
        Agent agent = agent(new Settings(dir.resolve("none.properties")));
        source.played(START, "Sanah", "Szampan");

        agent.tick();

        assertTrue(server.calls.isEmpty());
        assertEquals(false, shown.get().paired());
    }
}
