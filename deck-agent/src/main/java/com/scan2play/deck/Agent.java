package com.scan2play.deck;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The program's work, a look every few seconds ({@link #tick}): each DJ program's history read again when it changed, its new songs
 * sent to Scan2Play — at most {@value #MAX_PER_CALL} a call, the rest at the next look —, the requests the server marked told to the
 * window. The songs that started more than 10 minutes before the program did are the set before it (or another day's): not sent.
 * With nothing to send the program still calls once a minute, so the panel shows it alive and a "Odłącz" there is heard.
 */
final class Agent {

    static final int MAX_PER_CALL = 20;
    static final int MAX_WAITING = 200;
    static final Duration EARLIER_SET = Duration.ofMinutes(10);
    static final Duration ALIVE_EVERY = Duration.ofMinutes(1);

    /** What the window shows. */
    record Status(boolean paired, List<String> reading, Instant checkedAt, List<String> marked, ServerClient.Outcome last) {
    }

    private final List<HistorySource> sources;
    private final ServerClient client;
    private final Settings settings;
    private final Clock clock;
    private final Consumer<Status> window;
    private final Instant started;
    private final Map<HistorySource, String> versions = new HashMap<>();
    private final Set<HistorySource> readOnce = new HashSet<>();
    private final Set<String> seen = new HashSet<>();
    private final List<Played> waiting = new ArrayList<>();
    private Instant lastCall = Instant.EPOCH;
    private List<String> lastMarked = List.of();
    private ServerClient.Outcome lastOutcome;

    Agent(List<HistorySource> sources, ServerClient client, Settings settings, Clock clock, Consumer<Status> window) {
        this.sources = sources;
        this.client = client;
        this.settings = settings;
        this.clock = clock;
        this.window = window;
        this.started = clock.instant();
    }

    synchronized void tick() {
        Instant now = clock.instant();
        List<String> reading = new ArrayList<>();
        for (HistorySource source : sources) {
            if (!source.present()) {
                continue;
            }
            try {
                String version = source.version();
                if (!version.isEmpty() && !version.equals(versions.get(source))) {
                    read(source, source.songs());
                    versions.put(source, version);
                }
                if (!version.isEmpty()) {
                    reading.add(source.program() + " (" + source.reading() + ")");
                }
            } catch (IOException | RuntimeException e) {
                versions.remove(source);   // a file being written, a database busy: read again at the next look
            }
        }
        String token = settings.token();
        if (token != null && (!waiting.isEmpty() || Duration.between(lastCall, now).compareTo(ALIVE_EVERY) >= 0)) {
            send(token, now);
        }
        window.accept(new Status(settings.token() != null, reading, now, lastMarked, lastOutcome));
    }

    private void read(HistorySource source, List<Played> songs) {
        boolean first = readOnce.add(source);
        Instant earlier = started.minus(EARLIER_SET);
        for (Played song : songs) {
            if (!seen.add(source.program() + "|" + song.identity())) {
                continue;
            }
            boolean fresh = song.at() == null ? !first : !song.at().isBefore(earlier);
            if (fresh) {
                waiting.add(song);
                if (waiting.size() > MAX_WAITING) {
                    waiting.removeFirst();   // not paired for a long while: tonight's last songs are the ones worth sending
                }
            }
        }
    }

    private void send(String token, Instant now) {
        List<Played> batch = List.copyOf(waiting.subList(0, Math.min(MAX_PER_CALL, waiting.size())));
        lastCall = now;
        ServerClient.Answer answer = client.plays(settings.server(), token, batch);
        lastOutcome = answer.outcome();
        switch (answer.outcome()) {
            case OK -> {
                waiting.subList(0, batch.size()).clear();
                if (!answer.marked().isEmpty()) {
                    lastMarked = answer.marked();
                }
            }
            case DISCONNECTED -> {
                try {
                    settings.disconnected();
                } catch (IOException e) {
                    // the token is refused anyway; the window asks for a new code
                }
            }
            default -> { }   // kept for the next look
        }
    }

    /** The songs read and not sent yet (the tests). */
    synchronized List<Played> waiting() {
        return List.copyOf(waiting);
    }
}
