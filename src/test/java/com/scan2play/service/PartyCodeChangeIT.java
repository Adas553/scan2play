package com.scan2play.service;

import com.scan2play.PostgresIntegrationTest;
import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.entity.SongRequestEntity;
import com.scan2play.repository.PartySettingsRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * "Zmień kod imprezy" (V49) on a real PostgreSQL: everything of the party moves to the new code — its requests, feedback, staff,
 * invitations, library and logo —, nothing stays under the old one, and the old code is never given again (a new party's nor a new
 * code); a guest's request the AI was still judging is not saved under the old code.
 */
class PartyCodeChangeIT extends PostgresIntegrationTest {

    @Autowired PartyCodeService codes;
    @Autowired PartySettingsQueryService queries;
    @Autowired SongRequestCommandService commands;
    @Autowired PartySettingsRepository parties;
    @Autowired CacheManager cacheManager;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;

    private static final List<String> TABLES = List.of("song_requests", "feedback", "party_staff", "staff_invitation", "dj_library",
            "dj_library_track", "party_logo");

    private PartySettingsEntity party(String owner) {
        return parties.saveAndFlush(PartySettingsEntity.builder().partyCode(newPartyCode()).ownerId(owner).active(true)
                .ownerName("Ola").build());
    }

    private int count(String table, String partyCode) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE party_code = ?", Integer.class, partyCode);
    }

    /** One row of the party in every table that holds its code. */
    private void everythingOf(String code, String owner) {
        jdbc.update("INSERT INTO song_requests (party_code, song_name, decision, requested_at) VALUES (?, 'Wilki - Baśka', 'accepted', now())", code);
        jdbc.update("INSERT INTO feedback (party_code, owner_id, message) VALUES (?, ?, 'Super')", code, owner);
        jdbc.update("INSERT INTO party_staff (party_code, member_id, permissions, joined_at) VALUES (?, ?, 'QUEUE', now())", code, owner + "-kasia");
        jdbc.update("INSERT INTO staff_invitation (party_code, email, email_key, permissions, invited_at) VALUES (?, ?, ?, 'QUEUE', now())",
                code, owner + "@gmail.com", owner + "@gmail.com");
        jdbc.update("INSERT INTO dj_library (party_code, track_count, format, uploaded_at) VALUES (?, 1, 'M3U', now())", code);
        jdbc.update("INSERT INTO dj_library_track (party_code, title_key, artists) VALUES (?, 'baska', 'wilki')", code);
        jdbc.update("INSERT INTO party_logo (party_code, png, width, height, version, updated_at) VALUES (?, ?, 1, 1, 'v1', now())",
                code, new byte[]{1});
        jdbc.update("UPDATE party_settings SET logo_version = 'v1' WHERE party_code = ?", code);
    }

    @Test
    void everythingOfTheParty_movesToTheNewCode_theOldOneIsRetired() {
        String owner = "code-" + System.nanoTime();
        String oldCode = party(owner).getPartyCode();
        everythingOf(oldCode, owner);
        queries.getSettings(oldCode);   // the party in the cache under the old code

        String newCode = codes.changeCode(oldCode);

        assertThat(newCode).isNotEqualTo(oldCode).hasSize(5);
        for (String table : TABLES) {
            assertThat(count(table, oldCode)).as(table + " under the old code").isZero();
            assertThat(count(table, newCode)).as(table + " under the new code").isEqualTo(1);
        }
        PartySettingsEntity moved = parties.findByOwnerId(owner).orElseThrow();
        assertThat(moved.getPartyCode()).isEqualTo(newCode);
        assertThat(moved.getOwnerName()).as("the settings stay").isEqualTo("Ola");
        assertThat(cacheManager.getCache(PartySettingsQueryService.CACHE).get(oldCode)).as("the old code forgotten").isNull();
        assertThatThrownBy(() -> queries.getSettings(oldCode)).isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM party_code_retired WHERE party_code = ?", Integer.class, oldCode)).isOne();
    }

    /** A retired code, the next random one: never given again — to a new code nor to a new DJ's party. */
    @Test
    void aRetiredCode_isNeverGivenAgain() {
        String owner = "code-" + System.nanoTime();
        String oldCode = party(owner).getPartyCode();
        String taken = party(owner + "-other").getPartyCode();
        codes.changeCode(oldCode);
        String free = newPartyCode();

        Iterator<String> candidates = List.of(oldCode, taken, free).iterator();
        assertThat(codes.freshCode(candidates::next)).as("the retired one and a party's skipped").isEqualTo(free);
        assertThatThrownBy(() -> codes.freshCode(() -> oldCode)).isInstanceOf(IllegalStateException.class);
    }

    /**
     * A guest's request the AI was still judging when the code changed: it waits for the change, then finds no party under the old
     * code and saves nothing — no request lost in a code nobody reads.
     */
    @Test
    void aRequestInFlight_isNotSavedUnderTheOldCode() throws Exception {
        String owner = "code-" + System.nanoTime();
        String oldCode = party(owner).getPartyCode();
        ExecutorService guest = Executors.newSingleThreadExecutor();
        List<Future<?>> ask = new ArrayList<>();
        try {
            new TransactionTemplate(transactions).executeWithoutResult(status -> {
                codes.changeCode(oldCode);
                ask.add(guest.submit(() -> commands.saveOrVote(SongRequestEntity.builder().partyCode(oldCode)
                        .songName("Wilki - Baśka").style("ANY").decision("accepted").requestedAt(Instant.now()).build(), Set.of())));
                awaitLockWaiters(jdbc, 1);
            });
            assertThatThrownBy(() -> ask.getFirst().get(30, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                    .cause().isInstanceOf(IllegalArgumentException.class);
        } finally {
            guest.shutdownNow();
        }

        assertThat(count("song_requests", oldCode)).isZero();
    }
}
