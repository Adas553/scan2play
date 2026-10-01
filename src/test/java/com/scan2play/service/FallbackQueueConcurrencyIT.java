package com.scan2play.service;

import com.scan2play.PostgresIntegrationTest;
import com.scan2play.model.MoveDirection;
import com.scan2play.repository.FallbackTrackRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.scan2play.model.FallbackTrackStatus.QUEUED;
import static com.scan2play.service.FallbackQueueIT.videos;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Players taking tracks while the DJ moves, drags and re-orders them, all at once, on a real PostgreSQL — the case in which the
 * statements that update many rows used to lock them in different orders and deadlock (found once, by hand). The advisory lock of
 * {@link FallbackTrackCommandService} is what keeps it apart; this test fails if it ever goes.
 */
class FallbackQueueConcurrencyIT extends PostgresIntegrationTest {

    private static final String PLAYLIST = "PLit000000000000000000000000000003";
    private static final String[] VIDEOS = {"a", "b", "c", "d", "e", "f", "g"};
    private static final int TAKERS = 4;
    private static final int TAKES_EACH = 35;
    private static final int MOVERS = 4;

    @Autowired FallbackTrackCommandService commands;
    @Autowired FallbackTrackRepository tracks;
    @Autowired JdbcTemplate jdbc;

    @Test
    void takesAndMovesAtOnceNeitherDeadlockNorLoseOrRepeatATrack() throws Exception {
        String party = newPartyCode();
        commands.replaceTracks(party, PLAYLIST, videos(VIDEOS), false);

        ExecutorService pool = Executors.newFixedThreadPool(TAKERS + MOVERS);
        CountDownLatch start = new CountDownLatch(1);
        AtomicBoolean taking = new AtomicBoolean(true);
        ConcurrentLinkedQueue<Long> playIds = new ConcurrentLinkedQueue<>();
        List<Future<?>> work = new ArrayList<>();
        try {
            for (int t = 0; t < TAKERS; t++) {
                work.add(pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < TAKES_EACH; i++) {
                        playIds.add(commands.takeNextTrack(party, PLAYLIST, false).orElseThrow().getId());
                    }
                    return null;
                }));
            }
            for (int m = 0; m < MOVERS; m++) {
                work.add(pool.submit(() -> {
                    start.await();
                    ThreadLocalRandom random = ThreadLocalRandom.current();
                    while (taking.get()) {
                        List<Long> queued = tracks.findByPartyCodeAndPlaylistIdAndStatus(party, PLAYLIST, QUEUED,
                                PageRequest.of(0, 100)).stream().map(t -> t.getId()).toList();
                        if (queued.isEmpty()) {
                            continue;
                        }
                        Long track = queued.get(random.nextInt(queued.size()));
                        switch (random.nextInt(5)) {   // the result may be false: the player took the track meanwhile
                            case 0 -> commands.moveTrack(party, PLAYLIST, track, MoveDirection.UP);
                            case 1 -> commands.moveTrack(party, PLAYLIST, track, MoveDirection.TOP);
                            case 2 -> commands.placeTrack(party, PLAYLIST, track, queued.get(random.nextInt(queued.size())));
                            case 3 -> commands.placeTrack(party, PLAYLIST, track, null);
                            default -> commands.applyShuffleSetting(party, PLAYLIST, random.nextBoolean());
                        }
                    }
                    return null;
                }));
            }
            start.countDown();
            for (int t = 0; t < TAKERS; t++) {
                work.get(t).get(2, TimeUnit.MINUTES);   // a deadlock or a lost track ends here as an exception
            }
            taking.set(false);
            for (Future<?> mover : work.subList(TAKERS, work.size())) {
                mover.get(1, TimeUnit.MINUTES);
            }
        } finally {
            taking.set(false);
            pool.shutdownNow();
        }

        int total = TAKERS * TAKES_EACH;
        assertThat(playIds).doesNotHaveDuplicates().hasSize(total);

        // the log in the order of the hand-outs: every complete round holds every track exactly once
        List<String> log = jdbc.queryForList("SELECT video_id FROM fallback_play WHERE party_code = ? ORDER BY id", String.class, party);
        assertThat(log).hasSize(total);
        for (int round = 0; round < total / VIDEOS.length; round++) {
            assertThat(log.subList(round * VIDEOS.length, (round + 1) * VIDEOS.length)).as("round %d", round)
                    .containsExactlyInAnyOrder(VIDEOS);
        }

        // the queue still holds each track once, queued or playing
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT video_id, status FROM fallback_track WHERE party_code = ?", party);
        assertThat(rows).extracting(row -> row.get("video_id")).containsExactlyInAnyOrder((Object[]) VIDEOS);
        assertThat(rows).extracting(row -> row.get("status")).isSubsetOf("QUEUED", "PLAYED");
    }
}
