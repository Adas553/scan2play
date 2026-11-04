package com.scan2play.service;

import com.scan2play.PostgresIntegrationTest;
import com.scan2play.entity.EveningSummaryEntity;
import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.entity.SongRequestEntity;
import com.scan2play.repository.EveningSummaryRepository;
import com.scan2play.repository.PartySettingsRepository;
import com.scan2play.repository.SongRequestRepository;
import com.scan2play.util.Times;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * "Podsumowanie wieczoru" beyond the guests' requests (V61) on a real PostgreSQL: what "Wyczyść historię" and the 30 days' purge
 * delete is kept in {@code evening_song} by the same statement — a song's name, times, status and counts, never a guest's words, no
 * name of a request the AI rejected —, the summary reads an evening from both tables, the evenings' list counts both, the title and
 * the hosts' link are one row an evening, and 13 months later all of it goes.
 */
class EveningSummaryIT extends PostgresIntegrationTest {

    private static final LocalDate EVENING = LocalDate.of(2026, 10, 3);

    @Autowired DjService djService;
    @Autowired EveningSummaryService summaries;
    @Autowired SongRequestRetentionService retention;
    @Autowired SongRequestRepository requests;
    @Autowired EveningSummaryRepository evenings;
    @Autowired PartySettingsRepository parties;
    @Autowired JdbcTemplate jdbc;

    private static Instant at(int hour, int minute) {
        LocalDate day = hour < 6 ? EVENING.plusDays(1) : EVENING;
        return LocalDateTime.of(day, java.time.LocalTime.of(hour, minute)).atZone(Times.DISPLAY_ZONE).toInstant();
    }

    private String party() {
        String code = newPartyCode();
        parties.saveAndFlush(PartySettingsEntity.builder().partyCode(code).ownerId("owner-" + code).active(true).build());
        return code;
    }

    private SongRequestEntity save(String party, String song, String decision, Instant asked, Instant played, int votes) {
        return requests.saveAndFlush(SongRequestEntity.builder().partyCode(party).songName(song).style("ANY").decision(decision)
                .requestedAt(asked).playedAt(played).votes(votes).build());
    }

    /**
     * "Wyczyść historię": the played and rejected requests are deleted, each kept in {@code evening_song} by the same statement —
     * the waiting one and the one skipped within two hours stay where they are, not copied. The summary of the evening is then the
     * same as before it.
     */
    @Test
    void clearingTheHistory_keepsWhatTheSummaryCounts_withoutTheGuestsWords() {
        String party = party();
        SongRequestEntity played = save(party, "Varius Manx - Orła cień", "played", at(20, 0), at(20, 30), 4);
        played.setGuestText("orła cień dla Ani");
        played.setTips(2);
        played.setDedications(1);
        requests.saveAndFlush(played);
        SongRequestEntity rejected = save(party, "dla Ani od Marka", "rejected", at(20, 10), null, 1);
        rejected.setDjComment("Nie tym razem");
        requests.saveAndFlush(rejected);
        SongRequestEntity skippedLongAgo = save(party, "Kult - Arahja", "rejected", at(21, 0), null, 2);
        skippedLongAgo.setSkippedAt(at(21, 5));
        requests.saveAndFlush(skippedLongAgo);
        SongRequestEntity cleared = save(party, "Bajm - Biała armia", "rejected", at(22, 0), null, 3);
        cleared.setClearedAt(at(23, 0));
        requests.saveAndFlush(cleared);
        SongRequestEntity waiting = save(party, "Sanah - Szampan", "accepted", at(23, 30), null, 5);
        SongRequestEntity skippedNow = save(party, "Wilki - Baśka", "rejected", Instant.now().minus(Duration.ofMinutes(10)), null, 1);
        skippedNow.setSkippedAt(Instant.now());
        requests.saveAndFlush(skippedNow);
        EveningSummaryService.Summary before = EveningSummaryService.summarize(EVENING, summaries.requests(party, EVENING));

        assertThat(djService.clearHistory(party)).isEqualTo(4);

        assertThat(jdbc.queryForList("SELECT id FROM song_requests WHERE party_code = ? ORDER BY id", Long.class, party))
                .containsExactly(waiting.getId(), skippedNow.getId());
        List<Map<String, Object>> kept = jdbc.queryForList("SELECT * FROM evening_song WHERE party_code = ? ORDER BY requested_at", party);
        assertThat(kept).extracting(row -> row.get("song_request_id"))
                .containsExactly(played.getId(), rejected.getId(), skippedLongAgo.getId(), cleared.getId());
        assertThat(kept).extracting(row -> row.get("status")).containsExactly("PLAYED", "REJECTED_BY_AI", "SKIPPED", "CLEARED");
        assertThat(kept.get(1).get("song_name")).as("what the guest typed, rejected by the AI: not kept").isNull();
        assertThat(kept.getFirst()).containsEntry("song_name", "Varius Manx - Orła cień").containsEntry("votes", 4)
                .containsEntry("tips", 2).containsEntry("dedications", 1);
        assertThat(jdbc.queryForList("SELECT column_name FROM information_schema.columns WHERE table_name = 'evening_song'", String.class))
                .as("nothing a guest wrote").doesNotContain("guest_text", "dj_comment", "style", "track_url");

        EveningSummaryService.Summary after = EveningSummaryService.summarize(EVENING, summaries.requests(party, EVENING));
        assertThat(List.of(after.played(), after.votes(), after.songs(), after.rejectedByAi(), after.skipped(), after.cleared(),
                after.waiting(), after.tips(), after.dedications()))
                .isEqualTo(List.of(before.played(), before.votes(), before.songs(), before.rejectedByAi(), before.skipped(),
                        before.cleared(), before.waiting(), before.tips(), before.dedications()));
        assertThat(after.top()).extracting(EveningSummaryService.Song::name).isEqualTo(before.top().stream().map(EveningSummaryService.Song::name).toList());
        // the evenings' list counts both tables: 4 + 2 + 3 + 5 of what the AI let through, the evening of the recent skip apart
        assertThat(requests.findEvenings(party, 52)).extracting(row -> row[0] + " " + ((Number) row[1]).longValue())
                .contains("2026-10-03 14");
    }

    /**
     * The 30 days' purge keeps the evening's songs the same way; a request of a party that is no more is deleted without a copy
     * (no evening to keep, and no foreign key broken).
     */
    @Test
    void thePurge_keepsTheSongs_ofAPartyThatIsThere() {
        String party = party();
        String gone = newPartyCode();
        Instant old = Instant.now().minus(Duration.ofDays(40));
        SongRequestEntity played = save(party, "Varius Manx - Orła cień", "played", old, old.plusSeconds(600), 3);
        save(gone, "Kult - Arahja", "played", old, old.plusSeconds(600), 1);

        retention.purgeRequestedBefore(Instant.now().minus(Duration.ofDays(30)));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM song_requests WHERE party_code IN (?, ?)", Integer.class, party, gone)).isZero();
        assertThat(jdbc.queryForList("SELECT song_request_id FROM evening_song WHERE party_code = ?", Long.class, party))
                .containsExactly(played.getId());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM evening_song WHERE party_code = ?", Integer.class, gone)).isZero();
        LocalDate evening = EveningSummaryService.eveningOf(old);
        assertThat(summaries.evenings(party)).extracting(EveningSummaryService.Evening::day).contains(evening);
        assertThat(EveningSummaryService.summarize(evening, summaries.requests(party, evening)).played()).isEqualTo(1);
    }

    /** The title and the hosts' link: one row an evening, saved twice it stays one; a link's secret is no other evening's. */
    @Test
    void theTitleAndTheLink_areOneRowAnEvening() {
        String party = party();
        summaries.saveTitle(party, EVENING, "  Wesele   Ani i Tomka ");
        String token = summaries.share(party, EVENING);
        summaries.saveTitle(party, EVENING, "Wesele Ani i Tomka 💍");

        EveningSummaryEntity evening = evenings.findByPartyCodeAndEvening(party, EVENING).orElseThrow();
        assertThat(evening.getTitle()).isEqualTo("Wesele Ani i Tomka 💍");
        assertThat(evening.getShareToken()).isEqualTo(token);
        assertThat(evening.getShareExpiresAt()).isBetween(Instant.now().plus(Duration.ofDays(29)), Instant.now().plus(Duration.ofDays(31)));
        assertThat(summaries.shared(token)).map(EveningSummaryEntity::getEvening).contains(EVENING);

        String newer = summaries.share(party, EVENING);
        assertThat(summaries.shared(token)).as("a new link: the old one stops").isEmpty();
        assertThat(summaries.shared(newer)).isPresent();
        summaries.stopSharing(party, EVENING);
        assertThat(summaries.shared(newer)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM evening_summary WHERE party_code = ?", Integer.class, party)).isOne();

        String other = party();
        jdbc.update("INSERT INTO evening_summary (party_code, evening, share_token, updated_at) VALUES (?, ?, 'same', now())", other, EVENING);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO evening_summary (party_code, evening, share_token, updated_at)"
                + " VALUES (?, ?, 'same', now())", other, EVENING.plusDays(1))).hasMessageContaining("uk_evening_summary_share_token");
    }

    /** 13 months after the evening its kept songs, its title and its link go (the nightly purge). */
    @Test
    void after13Months_theEveningGoes() {
        String party = party();
        LocalDate before = LocalDate.now(Times.DISPLAY_ZONE).minusMonths(SongRequestRetentionService.SUMMARY_MONTHS);
        Instant tooOld = before.minusDays(1).atTime(21, 0).atZone(Times.DISPLAY_ZONE).toInstant();
        Instant young = before.plusDays(1).atTime(21, 0).atZone(Times.DISPLAY_ZONE).toInstant();
        jdbc.update("INSERT INTO evening_song (song_request_id, party_code, song_name, requested_at, status, votes, tips, dedications,"
                + " added_by_dj, via_kiosk) VALUES (?, ?, 'old', ?, 'PLAYED', 1, 0, 0, false, false), (?, ?, 'young', ?, 'PLAYED', 1, 0, 0, false, false)",
                -System.nanoTime(), party, java.sql.Timestamp.from(tooOld), -System.nanoTime() - 1, party, java.sql.Timestamp.from(young));
        summaries.saveTitle(party, before.minusDays(1), "old");
        summaries.saveTitle(party, before.plusDays(1), "young");

        retention.purgeSummariesBefore(before);

        assertThat(jdbc.queryForList("SELECT song_name FROM evening_song WHERE party_code = ?", String.class, party)).containsExactly("young");
        assertThat(jdbc.queryForList("SELECT title FROM evening_summary WHERE party_code = ?", String.class, party)).containsExactly("young");
    }
}
