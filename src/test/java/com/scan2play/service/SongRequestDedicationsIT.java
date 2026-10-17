package com.scan2play.service;

import com.scan2play.PostgresIntegrationTest;
import com.scan2play.entity.SongRequestDedicationEntity;
import com.scan2play.entity.SongRequestEntity;
import com.scan2play.repository.SongRequestDedicationRepository;
import com.scan2play.service.SongRequestCommandService.Dedication;
import com.scan2play.service.SongRequestCommandService.Outcome;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The guests' dedications (V38) on a real PostgreSQL: saved under the party's requests' lock with a new song, a vote and the guest's
 * own song asked again — each counted on the song, which changes the queue's fingerprint (the DJ's open panel fetches the rows) —;
 * none with a rejected song; the queue reads the newest few of each song; deleted with the request.
 */
class SongRequestDedicationsIT extends PostgresIntegrationTest {

    private static final String LINK = "https://www.youtube.com/results?search_query=sanah";

    @Autowired SongRequestCommandService commands;
    @Autowired SongRequestDedicationRepository dedications;
    @Autowired DjService dj;
    @Autowired JdbcTemplate jdbc;

    private String newParty() {
        String party = newPartyCode();
        jdbc.update("INSERT INTO party_settings (party_code, owner_id, active, global_vibe, request_limit, cooldown_minutes,"
                + " duplicate_check_window) VALUES (?, ?, true, 'ANY', 2, 3, 15)", party, "owner-" + party);
        return party;
    }

    private static SongRequestEntity request(String party, String name, String decision) {
        return SongRequestEntity.builder().partyCode(party).songName(name).style("ANY").decision(decision).trackUrl(LINK)
                .requestedAt(Instant.now()).build();
    }

    private List<Map<String, Object>> rows(long songId) {
        return jdbc.queryForList("SELECT text, checked FROM song_request_dedications WHERE song_request_id = ? ORDER BY id", songId);
    }

    private int counted(long songId) {
        return jdbc.queryForObject("SELECT dedications FROM song_requests WHERE id = ?", Integer.class, songId);
    }

    @Test
    void aDedication_isSavedWithANewSong_aVote_andTheGuestsOwnSong_andChangesTheQueuesFingerprint() {
        String party = newParty();
        SongRequestCommandService.Saved first = commands.saveOrVote(request(party, "sanah - Szampan", "accepted"), Set.of(),
                new Dedication("dla Ani", true));
        long song = first.request().getId();
        assertThat(first.dedicationAdded()).isTrue();

        SongRequestCommandService.Saved vote = commands.saveOrVote(request(party, "Sanah – Szampan", "accepted"), Set.of(),
                new Dedication("od świadków", false));
        assertThat(vote.outcome()).isEqualTo(Outcome.VOTE);
        assertThat(vote.dedicationAdded()).isTrue();

        // the guest who asked first asks again with a dedication: no vote, the dedication added — and the DJ's panel must see it
        String before = dj.getQueueFingerprint(party);
        SongRequestCommandService.Saved own = commands.saveOrVote(request(party, "sanah - Szampan", "accepted"), Set.of(song),
                new Dedication("jeszcze raz dla Ani", true));
        assertThat(own.outcome()).isEqualTo(Outcome.ALREADY_YOURS);
        assertThat(own.dedicationAdded()).isTrue();
        assertThat(dj.getQueueFingerprint(party)).as("the ETag of the DJ's poll").isNotEqualTo(before);

        assertThat(rows(song)).extracting(row -> row.get("text")).containsExactly("dla Ani", "od świadków", "jeszcze raz dla Ani");
        assertThat(rows(song)).extracting(row -> row.get("checked")).containsExactly(true, false, true);
        assertThat(counted(song)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT votes FROM song_requests WHERE id = ?", Integer.class, song)).isEqualTo(2);

        // without a dedication: nothing more
        commands.saveOrVote(request(party, "sanah - Szampan", "accepted"), Set.of());
        assertThat(counted(song)).isEqualTo(3);
    }

    @Test
    void aRejectedSong_andASongTheDjSkipped_takeNoDedication() {
        String party = newParty();
        SongRequestCommandService.Saved rejected = commands.saveOrVote(request(party, "Nirvana - Lithium", "rejected"), Set.of(),
                new Dedication("dla Ani", true));
        assertThat(rejected.dedicationAdded()).isFalse();
        assertThat(rows(rejected.request().getId())).isEmpty();

        long skipped = commands.saveOrVote(request(party, "Wilki - Baśka", "accepted"), Set.of()).request().getId();
        dj.dismissSong(skipped, party);
        SongRequestCommandService.Saved again = commands.saveOrVote(request(party, "Wilki - Baśka", "accepted"), Set.of(),
                new Dedication("dla Ani", true));
        assertThat(again.outcome()).isEqualTo(Outcome.SKIPPED_BY_DJ);
        assertThat(again.dedicationAdded()).isFalse();
        assertThat(rows(skipped)).isEmpty();
    }

    /** A song keeps at most 20: a guest's own song asked again uses no limit of theirs, so a dedication there costs them nothing. */
    @Test
    void aSongKeepsAtMostTwentyDedications() {
        String party = newParty();
        long song = commands.saveOrVote(request(party, "sanah - Szampan", "accepted"), Set.of()).request().getId();
        for (int i = 1; i <= SongRequestCommandService.DEDICATIONS_PER_SONG + 3; i++) {
            commands.saveOrVote(request(party, "sanah - Szampan", "accepted"), Set.of(song), new Dedication("nr " + i, true));
        }
        assertThat(rows(song)).hasSize(SongRequestCommandService.DEDICATIONS_PER_SONG);
        assertThat(counted(song)).isEqualTo(SongRequestCommandService.DEDICATIONS_PER_SONG);
    }

    /** The queue shows the newest three of each song, oldest of them first, and reads nothing for a queue without one. */
    @Test
    void theQueue_readsTheNewestThreeOfEachSong() {
        String party = newParty();
        SongRequestEntity a = commands.saveOrVote(request(party, "Alpha", "accepted"), Set.of()).request();
        SongRequestEntity b = commands.saveOrVote(request(party, "Beta", "accepted"), Set.of()).request();
        SongRequestEntity c = commands.saveOrVote(request(party, "Gamma", "accepted"), Set.of()).request();
        for (int i = 1; i <= 5; i++) {
            commands.saveOrVote(request(party, "Alpha", "accepted"), Set.of(a.getId()), new Dedication("a" + i, true));
        }
        commands.saveOrVote(request(party, "Beta", "accepted"), Set.of(b.getId()), new Dedication("b1", true));

        Map<Long, List<SongRequestDedicationEntity>> shown = dj.dedicationsOf(dj.getWaiting(party));

        assertThat(shown.get(a.getId())).extracting(SongRequestDedicationEntity::getText).containsExactly("a3", "a4", "a5");
        assertThat(shown.get(b.getId())).extracting(SongRequestDedicationEntity::getText).containsExactly("b1");
        assertThat(shown).doesNotContainKey(c.getId());
        assertThat(dj.dedicationsOf(List.of(c))).isEmpty();
    }

    /** A party there before V38 (its row written without the column) has the dedications on: the default the DJ can switch off. */
    @Test
    void aPartyFromBefore_hasTheDedicationsOn() {
        String party = newParty();
        assertThat(jdbc.queryForObject("SELECT dedications_on FROM party_settings WHERE party_code = ?", Boolean.class, party)).isTrue();
    }

    /** Deleted with the request: the 30 days, "Wyczyść historię" and the account's deletion delete the requests. */
    @Test
    void theDedications_goWithTheirRequest() {
        String party = newParty();
        long song = commands.saveOrVote(request(party, "sanah - Szampan", "accepted"), Set.of(), new Dedication("dla Ani", true))
                .request().getId();

        jdbc.update("DELETE FROM song_requests WHERE id = ?", song);

        assertThat(rows(song)).isEmpty();
    }
}
