package com.scan2play.service;

import com.scan2play.PostgresIntegrationTest;
import com.scan2play.entity.FallbackPlayEntity;
import com.scan2play.entity.FallbackTrackEntity;
import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.model.FallbackTrackStatus;
import com.scan2play.model.MoveDirection;
import com.scan2play.model.MusicProviderType;
import com.scan2play.model.PlaylistTrack;
import com.scan2play.repository.FallbackPlayRepository;
import com.scan2play.repository.FallbackTrackRepository;
import com.scan2play.repository.PartySettingsRepository;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static com.scan2play.model.FallbackTrackStatus.CANCELLED;
import static com.scan2play.model.FallbackTrackStatus.PLAYED;
import static com.scan2play.model.FallbackTrackStatus.QUEUED;
import static com.scan2play.repository.FallbackTrackRepository.UPCOMING_ORDER;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The background queue ({@link FallbackTrackCommandService}, {@link FallbackQueueService}) on a real PostgreSQL: the order the
 * tracks are handed out in, the rounds, the DJ's moves, the skips, the purge and the version of the "up next" list — the SQL of
 * {@link FallbackTrackRepository} as the database runs it (the native shuffle and renumbering, the advisory lock, the
 * conditional claims), which the mocked {@code FallbackTrackCommandServiceTest} cannot reach.
 */
class FallbackQueueIT extends PostgresIntegrationTest {

    private static final String PLAYLIST = "PLit000000000000000000000000000001";
    private static final String OTHER_PLAYLIST = "PLit000000000000000000000000000002";

    @Autowired FallbackTrackCommandService commands;
    @Autowired FallbackQueueService queue;
    @Autowired FallbackTrackRepository tracks;
    @Autowired FallbackPlayRepository plays;
    @Autowired PartySettingsRepository parties;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManagerFactory entityManagerFactory;

    @Test
    void handsOutInPlaylistOrderAndLoops() {
        String party = newPartyCode();
        commands.replaceTracks(party, PLAYLIST, videos("a", "b", "c"), false);

        assertThat(take(party, 4)).containsExactly("a", "b", "c", "a");
        // the round started again as soon as its last track was handed out: b and c wait, a plays
        assertThat(upcoming(party)).containsExactly("b", "c");
        assertThat(plays.findRecent(party, PageRequest.of(0, 10))).extracting(FallbackPlayEntity::getVideoId)
                .containsExactly("a", "c", "b", "a");
    }

    @Test
    void anotherPlaylistCancelsTheQueueAndTheOldOneNeverPlaysAgain() {
        String party = newPartyCode();
        commands.replaceTracks(party, PLAYLIST, videos("a", "b", "c"), false);
        take(party, 1);
        commands.replaceTracks(party, OTHER_PLAYLIST, videos("x", "y"), false);

        assertThat(commands.takeNextTrack(party, PLAYLIST, false)).as("the old playlist is not revived").isEmpty();
        assertThat(commands.takeNextTrack(party, OTHER_PLAYLIST, false)).map(FallbackPlayEntity::getVideoId).contains("x");
        assertThat(statuses(party, PLAYLIST)).containsEntry(PLAYED, 1L).containsEntry(CANCELLED, 2L);
    }

    @Test
    void aShuffledPlaylistPlaysEveryTrackOncePerRoundAndNeverTheSameTwiceInARow() {
        String party = newPartyCode();
        commands.replaceTracks(party, PLAYLIST, videos("a", "b", "c"), true);

        List<String> played = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            played.add(commands.takeNextTrack(party, PLAYLIST, true).orElseThrow().getVideoId());
        }
        for (int round = 0; round < 10; round++) {
            assertThat(played.subList(round * 3, round * 3 + 3)).as("round %d of %s", round, played)
                    .containsExactlyInAnyOrder("a", "b", "c");
        }
        for (int i = 1; i < played.size(); i++) {
            assertThat(played.get(i)).as("track %d of %s", i, played).isNotEqualTo(played.get(i - 1));
        }
    }

    @Test
    void theDjMovesTracksAndTheOrderIsWhatPlays() {
        String party = newPartyCode();
        commands.replaceTracks(party, PLAYLIST, videos("a", "b", "c", "d", "e"), false);

        assertThat(commands.moveTrack(party, PLAYLIST, id(party, "c"), MoveDirection.UP)).isTrue();
        assertThat(upcoming(party)).containsExactly("a", "c", "b", "d", "e");
        assertThat(commands.moveTrack(party, PLAYLIST, id(party, "a"), MoveDirection.DOWN)).isTrue();
        assertThat(upcoming(party)).containsExactly("c", "a", "b", "d", "e");
        assertThat(commands.moveTrack(party, PLAYLIST, id(party, "e"), MoveDirection.TOP)).isTrue();
        assertThat(upcoming(party)).containsExactly("e", "c", "a", "b", "d");
        assertThat(commands.placeTrack(party, PLAYLIST, id(party, "c"), null)).isTrue();
        assertThat(upcoming(party)).containsExactly("e", "a", "b", "d", "c");
        assertThat(commands.placeTrack(party, PLAYLIST, id(party, "d"), id(party, "a"))).isTrue();
        assertThat(upcoming(party)).containsExactly("e", "d", "a", "b", "c");
        assertThat(tracks.existsByPartyCodeAndPlaylistIdAndStatusAndManualMoveTrue(party, PLAYLIST, QUEUED)).isTrue();

        assertThat(take(party, 2)).containsExactly("e", "d");
        assertThat(commands.moveTrack(party, PLAYLIST, idOfPlayed(party, "e"), MoveDirection.TOP))
                .as("a track the player took cannot be moved").isFalse();
        assertThat(commands.moveTrack(newPartyCode(), PLAYLIST, id(party, "a"), MoveDirection.TOP))
                .as("another party's track cannot be moved").isFalse();
    }

    @Test
    void shuffleOffContinuesAfterTheLastTrackHandedOut() {
        String party = newPartyCode();
        commands.replaceTracks(party, PLAYLIST, videos("a", "b", "c", "d", "e"), true);
        String last = take(party, 2).get(1);
        int position = "abcde".indexOf(last);

        commands.applyShuffleSetting(party, PLAYLIST, false);

        List<String> expected = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            String video = String.valueOf("abcde".charAt((position + i) % 5));
            if (tracks.findByPartyCodeAndPlaylistIdAndStatus(party, PLAYLIST, QUEUED, PageRequest.of(0, 10)).stream()
                    .anyMatch(t -> t.getVideoId().equals(video))) {
                expected.add(video);
            }
        }
        assertThat(upcoming(party)).containsExactlyElementsOf(expected).hasSize(3);
        assertThat(tracks.existsByPartyCodeAndPlaylistIdAndStatusAndManualMoveTrue(party, PLAYLIST, QUEUED)).isFalse();
    }

    @Test
    void aSkippedTrackComesBackInTheNextRound() {
        String party = newPartyCode();
        commands.replaceTracks(party, PLAYLIST, videos("a", "b", "c"), false);

        assertThat(commands.skipTrack(party, PLAYLIST, id(party, "b"), false)).isTrue();
        assertThat(take(party, 2)).containsExactly("a", "c");
        assertThat(upcoming(party)).containsExactly("a", "b", "c");
    }

    @Test
    void skippingTheLastQueuedTrackStartsTheNextRound() {
        String party = newPartyCode();
        commands.replaceTracks(party, PLAYLIST, videos("a", "b"), false);
        take(party, 1);

        assertThat(commands.skipTrack(party, PLAYLIST, id(party, "b"), false)).isTrue();
        // a (playing) and b (skipped) are both back for the new round, in playlist order
        assertThat(upcoming(party)).containsExactly("a", "b");
        assertThat(commands.skipTrack(newPartyCode(), PLAYLIST, id(party, "a"), false)).as("another party's track").isFalse();
    }

    @Test
    void aRefreshKeepsTheRoundAndTheDjsMoves() {
        String party = newPartyCode();
        commands.replaceTracks(party, PLAYLIST, videos("a", "b", "c", "d", "e"), false);
        take(party, 1);
        assertThat(commands.skipTrack(party, PLAYLIST, id(party, "b"), false)).isTrue();
        assertThat(commands.moveTrack(party, PLAYLIST, id(party, "e"), MoveDirection.TOP)).isTrue();
        jdbc.update("UPDATE fallback_track SET fetched_at = now() - interval '29 days' WHERE party_code = ?", party);
        Long idOfC = id(party, "c");

        // d left the playlist, f joined it, the titles changed
        commands.refreshTracks(party, PLAYLIST, List.of(new PlaylistTrack("a", "New a"), new PlaylistTrack("b", "New b"),
                new PlaylistTrack("c", "New c"), new PlaylistTrack("e", "New e"), new PlaylistTrack("f", "New f")), false);

        assertThat(upcoming(party)).as("the round goes on: the DJ's move stays, the new video comes last")
                .containsExactly("e", "c", "f");
        assertThat(id(party, "c")).as("a kept track keeps its row").isEqualTo(idOfC);
        assertThat(tracks.existsByPartyCodeAndPlaylistIdAndStatusAndManualMoveTrue(party, PLAYLIST, QUEUED)).isTrue();
        assertThat(jdbc.queryForMap("SELECT status, title FROM fallback_track WHERE party_code = ? AND video_id = 'a'", party))
                .as("the track that played stays played").containsEntry("status", "PLAYED").containsEntry("title", "New a");
        assertThat(statuses(party, PLAYLIST)).containsEntry(FallbackTrackStatus.SKIPPED, 1L).containsEntry(CANCELLED, 1L);
        assertThat(jdbc.queryForObject("SELECT status FROM fallback_track WHERE party_code = ? AND video_id = 'd'", String.class, party))
                .isEqualTo("CANCELLED");
        assertThat(jdbc.queryForList("SELECT DISTINCT fetched_at > now() - interval '1 minute' FROM fallback_track "
                + "WHERE party_code = ? AND status <> 'CANCELLED'", Boolean.class, party))
                .as("one fetch time, the new one: the purge starts counting anew").containsExactly(true);

        assertThat(take(party, 3)).containsExactly("e", "c", "f");
        assertThat(upcoming(party)).as("the next round is the refreshed playlist, in its new order")
                .containsExactly("a", "b", "c", "e", "f");
    }

    @Test
    void aRefreshMatchesAVideoThatIsInThePlaylistTwice() {
        String party = newPartyCode();
        commands.replaceTracks(party, PLAYLIST, videos("a", "b", "a"), false);
        take(party, 1);

        commands.refreshTracks(party, PLAYLIST, videos("a", "b", "a", "c"), false);

        assertThat(upcoming(party)).containsExactly("b", "a", "c");
        assertThat(statuses(party, PLAYLIST)).doesNotContainKey(CANCELLED);
    }

    @Test
    void aRefreshOfAPlaylistThatIsNotTheNewestImportImportsIt() {
        String party = newPartyCode();
        commands.replaceTracks(party, PLAYLIST, videos("a", "b"), false);
        commands.replaceTracks(party, OTHER_PLAYLIST, videos("x", "y"), false);

        commands.refreshTracks(party, PLAYLIST, videos("a", "b", "c"), false);

        assertThat(upcoming(party)).containsExactly("a", "b", "c");
        assertThat(statuses(party, OTHER_PLAYLIST)).containsOnlyKeys(CANCELLED);
    }

    @Test
    void thePurgeDeletesTracksAndPlaysFetchedMoreThan30DaysAgo() {
        String old = newPartyCode();
        String fresh = newPartyCode();
        commands.replaceTracks(old, PLAYLIST, videos("a", "b"), false);
        commands.replaceTracks(fresh, PLAYLIST, videos("a", "b"), false);
        take(old, 1);
        take(fresh, 1);
        jdbc.update("UPDATE fallback_track SET fetched_at = now() - interval '31 days' WHERE party_code = ?", old);
        jdbc.update("UPDATE fallback_play SET fetched_at = now() - interval '31 days' WHERE party_code = ?", old);

        commands.purgeStaleTracks();

        assertThat(count("fallback_track", old)).isZero();
        assertThat(count("fallback_play", old)).isZero();
        assertThat(count("fallback_track", fresh)).isEqualTo(2);
        assertThat(count("fallback_play", fresh)).isEqualTo(1);
    }

    @Test
    void theVersionOfTheListChangesWithEveryChangeAndOnlyThen() {
        String party = newPartyCode();
        parties.save(PartySettingsEntity.builder().partyCode(party).ownerId("owner-" + party)
                .activeProvider(MusicProviderType.YOUTUBE).fallbackShuffle(false)
                .fallbackPlaylistUrl("https://www.youtube.com/playlist?list=" + PLAYLIST).build());
        commands.replaceTracks(party, PLAYLIST, videos("a", "b", "c", "d", "e"), false);

        List<String> versions = new ArrayList<>();
        versions.add(queue.getVersion(party));
        assertThat(queue.getVersion(party)).as("nothing changed").isEqualTo(versions.get(0));

        commands.takeNextTrack(party, PLAYLIST, false);
        versions.add(queue.getVersion(party));
        commands.moveTrack(party, PLAYLIST, id(party, "d"), MoveDirection.UP);
        versions.add(queue.getVersion(party));
        commands.moveTrack(party, PLAYLIST, id(party, "d"), MoveDirection.DOWN);   // the same order again, but now "moved by hand"
        versions.add(queue.getVersion(party));
        commands.placeTrack(party, PLAYLIST, id(party, "b"), null);
        versions.add(queue.getVersion(party));
        commands.skipTrack(party, PLAYLIST, id(party, "c"), false);
        versions.add(queue.getVersion(party));
        commands.applyShuffleSetting(party, PLAYLIST, false);
        versions.add(queue.getVersion(party));
        commands.replaceTracks(party, PLAYLIST, videos("a", "b", "c", "d", "e"), false);
        versions.add(queue.getVersion(party));

        assertThat(new HashSet<>(versions)).as("every version differs: %s", versions).hasSameSizeAs(versions);
        assertThat(queue.getVersion(party)).isEqualTo(versions.get(versions.size() - 1));
    }

    /** Review item 1.4: an import of 500 tracks was 500 INSERTs under the queue's lock (identity ids defeat JDBC batching). */
    @Test
    void anImportIsAFewStatementsNotOnePerTrack() {
        String party = newPartyCode();
        String[] names = IntStream.range(0, 300).mapToObj(i -> "v" + i).toArray(String[]::new);
        Statistics statistics = statistics();

        statistics.clear();
        int imported = commands.replaceTracks(party, PLAYLIST, videos(names), false);

        assertThat(imported).isEqualTo(300);
        assertThat(statistics.getPrepareStatementCount()).as("statements of the import").isLessThan(10);
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT video_id, title, playlist_position, play_order, status, "
                + "manual_move, fetched_at, played_at FROM fallback_track WHERE party_code = ? ORDER BY playlist_position", party);
        assertThat(rows).hasSize(300);
        for (int i = 0; i < 300; i++) {
            Map<String, Object> row = rows.get(i);
            assertThat(row).containsEntry("video_id", "v" + i).containsEntry("title", "Song v" + i)
                    .containsEntry("playlist_position", i).containsEntry("play_order", i).containsEntry("status", "QUEUED")
                    .containsEntry("manual_move", false).containsEntry("played_at", null);
        }
        assertThat(rows).extracting(row -> row.get("fetched_at")).as("one import, one time").containsOnly(rows.get(0).get("fetched_at"));
        assertThat(take(party, 2)).containsExactly("v0", "v1");
    }

    @Test
    void anImportKeepsAMissingTitleAndShufflesWhenAsked() {
        String party = newPartyCode();
        commands.replaceTracks(party, PLAYLIST, List.of(new PlaylistTrack("a", null), new PlaylistTrack("b", "B")), true);

        assertThat(jdbc.queryForList("SELECT title FROM fallback_track WHERE party_code = ? ORDER BY playlist_position", String.class, party))
                .containsExactly(null, "B");
        assertThat(upcoming(party)).containsExactlyInAnyOrder("a", "b");
    }

    /** Review item 2.1: every lease report (every 3 s, every window) read the whole queue as entities to hash it. */
    @Test
    void theVersionReadsNoTracks() {
        String party = newPartyCode();
        parties.save(PartySettingsEntity.builder().partyCode(party).ownerId("owner-" + party)
                .activeProvider(MusicProviderType.YOUTUBE).fallbackShuffle(false)
                .fallbackPlaylistUrl("https://www.youtube.com/playlist?list=" + PLAYLIST).build());
        commands.replaceTracks(party, PLAYLIST, videos(IntStream.range(0, 300).mapToObj(i -> "v" + i).toArray(String[]::new)), false);
        String before = queue.getVersion(party);   // the settings are cached from here on, as in the running app
        Statistics statistics = statistics();

        statistics.clear();
        String version = queue.getVersion(party);

        assertThat(version).isEqualTo(before);
        assertThat(statistics.getEntityLoadCount()).as("entities read for the version").isZero();
        assertThat(statistics.getPrepareStatementCount()).as("statements of the version").isLessThanOrEqualTo(1);
    }

    private Statistics statistics() {
        return entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    }

    private List<String> take(String party, int count) {
        List<String> videos = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            videos.add(commands.takeNextTrack(party, PLAYLIST, false).orElseThrow().getVideoId());
        }
        return videos;
    }

    private List<String> upcoming(String party) {
        return tracks.findByPartyCodeAndPlaylistIdAndStatus(party, PLAYLIST, QUEUED, PageRequest.of(0, 100, UPCOMING_ORDER))
                .stream().map(FallbackTrackEntity::getVideoId).toList();
    }

    private Long id(String party, String video) {
        return tracks.findByPartyCodeAndPlaylistIdAndStatus(party, PLAYLIST, QUEUED, PageRequest.of(0, 100)).stream()
                .filter(t -> t.getVideoId().equals(video)).findFirst().orElseThrow().getId();
    }

    private Long idOfPlayed(String party, String video) {
        return jdbc.queryForObject("SELECT id FROM fallback_track WHERE party_code = ? AND video_id = ? AND status = 'PLAYED'",
                Long.class, party, video);
    }

    private Map<FallbackTrackStatus, Long> statuses(String party, String playlist) {
        return jdbc.queryForList("SELECT status FROM fallback_track WHERE party_code = ? AND playlist_id = ?", String.class,
                party, playlist).stream().collect(Collectors.groupingBy(FallbackTrackStatus::valueOf, Collectors.counting()));
    }

    private long count(String table, String party) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE party_code = ?", Long.class, party);
    }

    /** Tracks whose video IDs are the given names (the queue does not check their shape), titled after them. */
    static List<PlaylistTrack> videos(String... names) {
        return IntStream.range(0, names.length).mapToObj(i -> new PlaylistTrack(names[i], "Song " + names[i])).toList();
    }
}
