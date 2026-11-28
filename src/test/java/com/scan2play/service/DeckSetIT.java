package com.scan2play.service;

import com.scan2play.PostgresIntegrationTest;
import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.repository.PartySettingsRepository;
import com.scan2play.util.LibraryKey;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The night's set (V83, {@link DeckSetService}) on a real PostgreSQL: the songs of a call kept once (a song sent again, two computers
 * at once), nothing kept with "Zapisuj set wieczoru" off and the kept set deleted, the computer's 600 a day, "Już leciało" among the
 * DJ's last N of tonight by the library's keys, the guests' last ten, the evening's set and its count, the nightly purge — and the
 * plans of the reads on the party's index ({@code idx_deck_play_party_started}), the purge's on {@code idx_deck_play_started}.
 */
class DeckSetIT extends PostgresIntegrationTest {

    @Autowired DeckSetService sets;
    @Autowired PartySettingsRepository partyRepository;
    @Autowired JdbcTemplate jdbc;
    @Autowired PartySettingsCommandService settingsCommands;
    @Autowired PartySettingsQueryService settingsQueries;
    @Autowired DataSource dataSource;

    private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.SECONDS);

    private String party() {
        String code = newPartyCode('S');
        partyRepository.saveAndFlush(PartySettingsEntity.builder().partyCode(code).ownerId("set-" + code).active(true).build());
        return code;
    }

    private static DeckSetService.SetSong song(String artist, String title, Duration ago) {
        return new DeckSetService.SetSong(NOW.minus(ago), artist, title, "VirtualDJ");
    }

    private int rows(String party) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM deck_play WHERE party_code = ?", Integer.class, party);
        return n == null ? 0 : n;
    }

    @Test
    void theSongsOfACall_areKeptOnce_aSongSentAgainIsNot_andTheSameSongLaterIs() {
        String party = party();
        assertThat(sets.kept(party)).as("on by default").isTrue();

        assertThat(sets.keep(1, party, List.of(song("Wilki", "Baśka", Duration.ofMinutes(10)),
                song("Sanah", "Szampan", Duration.ofMinutes(6)), song("Sanah", "Szampan", Duration.ofMinutes(6).minusSeconds(1)),
                song("", "  ", Duration.ZERO), song("Kult", "Arahja", Duration.ofHours(13))), NOW)).as("two: no title, yesterday, twice").isEqualTo(2);
        // the program restarted: the same songs again, a second off; one new
        assertThat(sets.keep(1, party, List.of(song("Wilki", "Baśka", Duration.ofMinutes(10).minusSeconds(1)),
                song("Kayah & Bregović", "Prawy do lewego (Radio Edit)", Duration.ofMinutes(2))), NOW)).isEqualTo(1);
        // the DJ plays Baśka again an hour later: a second play
        assertThat(sets.keep(1, party, List.of(new DeckSetService.SetSong(NOW.plus(Duration.ofMinutes(50)), "Wilki", "Baśka", null)),
                NOW.plus(Duration.ofHours(1)))).isEqualTo(1);
        assertThat(rows(party)).isEqualTo(4);

        assertThat(sets.lastPlayed(party, NOW.plus(Duration.ofHours(1)))).extracting(DeckSetService.SetSong::name)
                .containsExactly("Wilki - Baśka", "Kayah & Bregović - Prawy do lewego (Radio Edit)", "Sanah - Szampan", "Wilki - Baśka");
    }

    @Test
    void twoComputersSendingTheSameSongAtOnce_keepItOnce() throws Exception {
        String party = party();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Integer>> kept = new ArrayList<>();
            for (long device = 1; device <= 2; device++) {
                long id = device;
                Callable<Integer> call = () -> sets.keep(id, party, List.of(song("Wilki", "Baśka", Duration.ofMinutes(1))), NOW);
                kept.add(pool.submit(call));
            }
            assertThat(kept.get(0).get() + kept.get(1).get()).isEqualTo(1);
        } finally {
            pool.shutdown();
        }
        assertThat(rows(party)).isEqualTo(1);
    }

    @Test
    void switchedOff_nothingIsKept_andTheKeptSetIsDeleted_onAgainKeepsAgain() {
        String party = party();
        String other = party();
        sets.keep(1, party, List.of(song("Wilki", "Baśka", Duration.ofMinutes(5))), NOW);
        sets.keep(2, other, List.of(song("Wilki", "Baśka", Duration.ofMinutes(5))), NOW);
        assertThat(sets.lastPlayed(party, NOW)).as("the guests' list in memory").hasSize(1);

        sets.setKept(party, false);
        assertThat(sets.kept(party)).isFalse();
        assertThat(rows(party)).isZero();
        assertThat(rows(other)).as("another party's set stays").isEqualTo(1);
        assertThat(sets.keep(1, party, List.of(song("Sanah", "Szampan", Duration.ofMinutes(1))), NOW)).isZero();
        assertThat(sets.lastPlayed(party, NOW)).isEmpty();

        sets.setKept(party, true);
        assertThat(sets.lastPlayed(party, NOW)).as("on again: nothing yet, in memory").isEmpty();
        assertThat(sets.keep(1, party, List.of(song("Sanah", "Szampan", Duration.ofMinutes(1))), NOW)).isEqualTo(1);
        assertThat(sets.lastPlayed(party, NOW)).as("the guests' list in memory follows the song kept")
                .extracting(DeckSetService.SetSong::name).containsExactly("Sanah - Szampan");
    }

    /**
     * Two writers of one party's set take turns (its advisory lock): a call waits while another — a second computer — keeps the same
     * song, and then keeps nothing; read without the lock, it would not see the other's song yet and keep it twice.
     */
    @Test
    void aCall_waitsForAnotherWriterOfTheParty_andDoesNotKeepItsSongAgain() throws Exception {
        String party = party();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection other = dataSource.getConnection()) {
            other.setAutoCommit(false);
            lockAndKeepIn(other, party, "Wilki", "Baśka");
            Future<Integer> call = pool.submit(() -> sets.keep(1, party, List.of(song("Wilki", "Baśka", Duration.ofMinutes(1))), NOW));
            Thread.sleep(500);
            assertThat(call.isDone()).as("waits for the other writer").isFalse();
            other.commit();
            assertThat(call.get(10, TimeUnit.SECONDS)).as("the song the other one kept").isZero();
        } finally {
            pool.shutdownNow();
        }
        assertThat(rows(party)).isEqualTo(1);
    }

    /** "Zapisuj set wieczoru" off while a call of the program keeps a song: the off waits for it and deletes that song too. */
    @Test
    void switchedOff_waitsForACallInFlight_andDeletesTheSongItKept() throws Exception {
        String party = party();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection other = dataSource.getConnection()) {
            other.setAutoCommit(false);
            lockAndKeepIn(other, party, "Wilki", "Baśka");
            Future<?> off = pool.submit(() -> sets.setKept(party, false));
            Thread.sleep(500);
            assertThat(off.isDone()).as("waits for the call").isFalse();
            other.commit();
            off.get(10, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertThat(rows(party)).as("nothing left of a set the DJ said not to keep").isZero();
    }

    /** As {@link DeckSetService#keep} writes: the party's lock, then a song — in {@code connection}'s open transaction. */
    private static void lockAndKeepIn(Connection connection, String party, String artist, String title) throws Exception {
        try (var lock = connection.prepareStatement("SELECT pg_advisory_xact_lock(hashtext(?))")) {
            lock.setString(1, "deck_play:" + party);
            lock.execute();
        }
        try (var insert = connection.prepareStatement("INSERT INTO deck_play (party_code, started_at, artist, title, title_key, artists_key)"
                + " VALUES (?, ?, ?, ?, ?, ?)")) {
            insert.setString(1, party);
            insert.setTimestamp(2, Timestamp.from(NOW.minus(Duration.ofMinutes(1))));
            insert.setString(3, artist);
            insert.setString(4, title);
            LibraryKey key = LibraryKey.of(artist, title);
            insert.setString(5, key.title());
            insert.setString(6, key.artists());
            insert.execute();
        }
    }

    /**
     * The songs' starts are kept as the instants they are, whatever the JVM's zone: in the night the clocks go back, 02:30 is there
     * twice, and a time written in the zone without its offset would be read an hour off.
     */
    @Test
    void theStarts_areKeptExactly_inTheHourTheClocksGoBack_whateverTheJvmsZone() {
        String party = party();
        Instant first = Instant.parse("2026-10-25T00:30:00Z");   // 02:30 summer time, the first of the two
        Instant second = Instant.parse("2026-10-25T01:30:00Z");  // 02:30 winter time
        TimeZone zone = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Europe/Warsaw"));
            sets.keep(1, party, List.of(new DeckSetService.SetSong(first, "Wilki", "Baśka", null),
                    new DeckSetService.SetSong(second, "Kult", "Arahja", null)), second.plusSeconds(60));
        } finally {
            TimeZone.setDefault(zone);
        }
        assertThat(jdbc.queryForList("SELECT started_at FROM deck_play WHERE party_code = ? ORDER BY started_at", Timestamp.class, party))
                .extracting(Timestamp::toInstant).containsExactly(first, second);
    }

    /** A title past the column's 200 characters is kept, cut — not a failed call that loses the whole set of it. */
    @Test
    void aVeryLongTitle_isKeptCut() {
        String party = party();
        assertThat(sets.keep(1, party, List.of(song("Wilki", "Baśka ".repeat(60), Duration.ofMinutes(1))), NOW)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT length(title) FROM deck_play WHERE party_code = ?", Integer.class, party))
                .isEqualTo(DeckSetService.FIELD_MAX);
    }

    @Test
    void aComputer_keepsAtMost600SongsADay() {
        String party = party();
        int kept = 0;
        // the first call 10 songs, then 20 each: the last one is cut in the middle at the 600th
        for (int call = 0; call < 31; call++) {
            List<DeckSetService.SetSong> songs = new ArrayList<>();
            for (int i = 0; i < (call == 0 ? 10 : 20); i++) {
                songs.add(song("Artist " + call, "Song " + i, Duration.ofMinutes(i)));
            }
            kept += sets.keep(99_000 + party.hashCode(), party, songs, NOW);
        }
        assertThat(kept).isEqualTo(DeckSetService.DAILY_PER_DEVICE);
        assertThat(rows(party)).isEqualTo(DeckSetService.DAILY_PER_DEVICE);
    }

    /** A guest's request of the evening, {@code ago} before now. */
    private void guestAsked(String party, Duration ago, boolean byTheDj) {
        jdbc.update("INSERT INTO song_requests (party_code, song_name, decision, requested_at, votes, tips, dedications, checked,"
                + " added_by_dj, via_kiosk) VALUES (?, 'Ktoś - Coś', 'accepted', ?, 1, 0, 0, true, ?, false)", party,
                Timestamp.from(NOW.minus(ago)), byTheDj);
    }

    @Test
    void alreadyPlayed_isASongAmongTheLastNOfTonight_byItsTitleAndAnArtist() {
        String party = party();
        guestAsked(party, Duration.ofMinutes(50), false);
        sets.keep(1, party, List.of(song("Wilki", "Baśka", Duration.ofMinutes(40)), song("Kayah & Goran Bregović", "Prawy do lewego", Duration.ofMinutes(30)),
                song("Sanah", "Szampan", Duration.ofMinutes(20)), song("Kult", "Arahja", Duration.ofMinutes(10))), NOW);

        assertThat(sets.playedLately(party, "Wilki - Baśka", 15, NOW)).contains(NOW.minus(Duration.ofMinutes(40)));
        assertThat(sets.playedLately(party, "Goran Bregović - Prawy do lewego (Radio Edit)", 15, NOW)).as("an artist in common, a version")
                .contains(NOW.minus(Duration.ofMinutes(30)));
        assertThat(sets.playedLately(party, "Inny Zespół - Baśka", 15, NOW)).as("another artist's song of that title").isEmpty();
        assertThat(sets.playedLately(party, "Baśka", 15, NOW)).as("a title alone").isEmpty();
        assertThat(sets.playedLately(party, "Wilki - Baśka", 3, NOW)).as("older than the DJ's last 3").isEmpty();
        assertThat(sets.playedLately(party, "Wilki - Baśka", 0, NOW)).as("\"Bez powtórek\" off").isEmpty();
        assertThat(sets.playedLately(party, "Wilki - Baśka", 15, NOW.plus(Duration.ofHours(12)))).as("not tonight any more").isEmpty();
        assertThat(sets.playedLately(newPartyCode('S'), "Wilki - Baśka", 15, NOW)).as("another party").isEmpty();
    }

    /** A soundcheck before the guests came is no "already played": only what played since the evening's first guest's request. */
    @Test
    void alreadyPlayed_leavesOutTheSoundcheckBeforeTheFirstGuestsRequest() {
        String party = party();
        sets.keep(1, party, List.of(song("Wilki", "Baśka", Duration.ofMinutes(150)), song("Sanah", "Szampan", Duration.ofMinutes(20))), NOW);
        guestAsked(party, Duration.ofHours(20), false);
        assertThat(sets.playedLately(party, "Sanah - Szampan", 15, NOW)).as("no guest asked tonight (yesterday's do not count): nothing")
                .isEmpty();

        guestAsked(party, Duration.ofMinutes(60), true);
        assertThat(sets.playedLately(party, "Sanah - Szampan", 15, NOW)).as("the DJ's own song is no guest").isEmpty();

        guestAsked(party, Duration.ofMinutes(60), false);
        assertThat(sets.playedLately(party, "Sanah - Szampan", 15, NOW)).contains(NOW.minus(Duration.ofMinutes(20)));
        assertThat(sets.playedLately(party, "Wilki - Baśka", 15, NOW)).as("the soundcheck, before the guests").isEmpty();
    }

    @Test
    void theEvening_isItsSetInOrder_countedTheSame() {
        String party = party();
        sets.keep(1, party, List.of(song("Kult", "Arahja", Duration.ofMinutes(10)), song("Wilki", "Baśka", Duration.ofMinutes(40))), NOW);

        assertThat(sets.evening(party, NOW.minus(Duration.ofHours(1)), NOW.plusSeconds(1))).extracting(DeckSetService.SetSong::name)
                .containsExactly("Wilki - Baśka", "Kult - Arahja");
        assertThat(sets.evening(party, NOW.minus(Duration.ofMinutes(20)), NOW)).hasSize(1);
        assertThat(sets.countEvening(party, NOW.minus(Duration.ofHours(1)), NOW.plusSeconds(1))).isEqualTo(2);
    }

    @Test
    void thePurge_deletesWhatStartedBeforeTheCutoff_batchByBatch() {
        String party = party();
        sets.keep(1, party, List.of(song("Kult", "Arahja", Duration.ofMinutes(10))), NOW);
        jdbc.update("INSERT INTO deck_play (party_code, started_at, title, title_key, artists_key) VALUES (?, ?, 'Old', 'old', ''),"
                + " (?, ?, 'Older', 'older', '')", party, Timestamp.from(NOW.minus(Duration.ofDays(31))), party,
                Timestamp.from(NOW.minus(Duration.ofDays(40))));
        Instant cutoff = NOW.minus(Duration.ofDays(DeckSetService.KEPT_DAYS));

        assertThat(sets.deleteStartedBefore(cutoff, 1)).isEqualTo(1);
        assertThat(sets.deleteStartedBefore(cutoff, 1)).isEqualTo(1);
        assertThat(sets.deleteStartedBefore(cutoff, 1)).isZero();
        assertThat(rows(party)).isEqualTo(1);
    }

    /** "Pokazuj na TV i kiosku, co teraz leci" (V84): on by default; switched, the cached settings the TV reads follow after the commit. */
    @Test
    void nowPlayingShown_isOnByDefault_andTheCachedSettingsFollowTheSwitch() {
        String party = party();
        assertThat(settingsQueries.getSettings(party).isNowPlayingShown()).isTrue();
        settingsCommands.setNowPlayingShown(party, false);
        assertThat(settingsQueries.getSettings(party).isNowPlayingShown()).isFalse();
        assertThat(jdbc.queryForObject("SELECT now_playing_shown FROM party_settings WHERE party_code = ?", Boolean.class, party)).isFalse();
        settingsCommands.setNowPlayingShown(party, true);
        assertThat(settingsQueries.getSettings(party).isNowPlayingShown()).isTrue();
    }

    @Test
    void aPartyDeleted_takesItsSetWithIt() {
        String party = party();
        sets.keep(1, party, List.of(song("Kult", "Arahja", Duration.ofMinutes(10))), NOW);
        jdbc.update("DELETE FROM party_settings WHERE party_code = ?", party);
        assertThat(rows(party)).isZero();
    }

    /**
     * The reads use the party's index, the purge the time's — checked with the planner made to prefer an index whenever one fits
     * (a table of a few test rows is read whole otherwise): a missing or wrong index shows here as a "Seq Scan".
     */
    @Test
    void theReadsAndThePurge_useTheirIndexes() {
        String party = party();
        sets.keep(1, party, List.of(song("Kult", "Arahja", Duration.ofMinutes(10))), NOW);
        jdbc.execute("ANALYZE deck_play");
        String lately = plan("SELECT started_at, title_key, artists_key FROM (SELECT started_at, title_key, artists_key FROM deck_play"
                + " WHERE party_code = '" + party + "' AND started_at >= now() - interval '1 hour' ORDER BY started_at DESC LIMIT 15) last"
                + " WHERE title_key = 'arahja'");
        String evening = plan("SELECT started_at, artist, title, program FROM deck_play WHERE party_code = '" + party + "'"
                + " AND started_at >= now() - interval '1 day' AND started_at < now() ORDER BY started_at LIMIT 1000");
        String purge = plan("DELETE FROM deck_play WHERE id IN (SELECT id FROM deck_play WHERE started_at < now() - interval '30 days' LIMIT 1000)");
        String guestsCame = plan("SELECT min(requested_at) FROM song_requests WHERE party_code = '" + party + "'"
                + " AND requested_at >= now() - interval '12 hours' AND NOT added_by_dj");
        assertThat(lately).contains("idx_deck_play_party_started").doesNotContain("Seq Scan on deck_play");
        assertThat(guestsCame).as("the evening's first guest's request: the party's own rows, not every party's of 12 h (V85)")
                .contains("idx_song_requests_party_guest_time");
        assertThat(evening).contains("idx_deck_play_party_started").doesNotContain("Seq Scan on deck_play");
        assertThat(purge).contains("idx_deck_play_started");
    }

    private String plan(String sql) {
        return jdbc.execute((java.sql.Connection connection) -> {
            try (var statement = connection.createStatement()) {
                statement.execute("SET enable_seqscan = off");
                StringBuilder lines = new StringBuilder();
                try (var result = statement.executeQuery("EXPLAIN " + sql)) {
                    while (result.next()) {
                        lines.append(result.getString(1)).append('\n');
                    }
                }
                statement.execute("RESET enable_seqscan");
                return lines.toString();
            }
        });
    }
}
