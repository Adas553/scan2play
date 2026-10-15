package com.scan2play.service;

import com.scan2play.PostgresIntegrationTest;
import com.scan2play.entity.FeedbackEntity;
import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.entity.SongRequestEntity;
import com.scan2play.model.StaffRole;
import com.scan2play.repository.FeedbackRepository;
import com.scan2play.repository.PartySettingsRepository;
import com.scan2play.repository.SongRequestRepository;
import com.scan2play.service.StaffInvitationService.AnswerOutcome;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
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
 * "Usuń konto" on a real PostgreSQL (review 2026-10-14 W9): it takes the party's locks in the order everything else takes them, so
 * a "Dołącz" at the same moment waits for it instead of deadlocking; a guest's request the AI was still judging is not saved after
 * the party is gone; and the party's requests go in one statement, not one by one.
 */
class AccountDeletionIT extends PostgresIntegrationTest {

    @Autowired AccountDeletionService deletion;
    @Autowired StaffInvitationService invitations;
    @Autowired SongRequestCommandService commands;
    @Autowired PartySettingsRepository parties;
    @Autowired SongRequestRepository requests;
    @Autowired FeedbackRepository feedback;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired EntityManagerFactory entityManagers;

    private PartySettingsEntity party(String owner) {
        return parties.saveAndFlush(PartySettingsEntity.builder().partyCode(newPartyCode()).ownerId(owner).active(true).build());
    }

    private int count(String table, String partyCode) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE party_code = ?", Integer.class, partyCode);
    }

    /** Holds the party's row in a transaction of its own while {@code whileHeld} runs, then commits. */
    private void holdingTheParty(String partyCode, Runnable whileHeld) {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT id FROM party_settings WHERE party_code = ? FOR UPDATE", Long.class, partyCode);
            whileHeld.run();
        });
    }

    /**
     * Someone answers "Dołącz" to an invitation by e-mail while the organiser deletes the account, the answer a moment first: both
     * finish, no deadlock, and nothing of the party stays — the person who joined goes with it. Seen red while the deletion took the
     * invitations' rows before the party's row ("Dołącz" takes them the other way round): PostgreSQL killed one of the two.
     */
    @Test
    void dolaczWhileTheAccountIsDeleted_waitsInsteadOfDeadlocking() throws Exception {
        String owner = "deleted-" + System.nanoTime();
        PartySettingsEntity pub = party(owner);
        String kasia = "kasia." + System.nanoTime() + "@gmail.com";
        invitations.invite(pub.getPartyCode(), kasia, StaffRole.QUEUE.permissions());
        long invitation = invitations.waitingAt(pub.getPartyCode()).getFirst().getId();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Future<?>> both = new ArrayList<>();
        try {
            holdingTheParty(pub.getPartyCode(), () -> {
                both.add(pool.submit(() -> invitations.accept(invitation, kasia, "kasia-" + System.nanoTime(), "Kasia").outcome()));
                awaitLockWaiters(jdbc, 1);
                both.add(pool.submit(() -> deletion.deleteAllUserData(owner)));
                awaitLockWaiters(jdbc, 2);
            });
            assertThat(both.get(0).get(30, TimeUnit.SECONDS)).isEqualTo(AnswerOutcome.JOINED);
            both.get(1).get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertThat(parties.findByPartyCode(pub.getPartyCode())).isEmpty();
        assertThat(count("party_staff", pub.getPartyCode())).isZero();
        assertThat(count("staff_invitation", pub.getPartyCode())).isZero();
    }

    /**
     * A guest's request the AI was still judging when the organiser deleted the account: it is not saved after the deletion (the
     * guest's words would have stayed 30 days although the account was gone). The deletion holds the party's lock of the guests'
     * requests until it commits; the request, waiting for it, then finds no party.
     */
    @Test
    void aRequestInFlight_isNotSavedAfterTheAccountIsDeleted() throws Exception {
        String owner = "deleted-" + System.nanoTime();
        String partyCode = party(owner).getPartyCode();
        ExecutorService guest = Executors.newSingleThreadExecutor();
        List<Future<?>> ask = new ArrayList<>();
        try {
            new TransactionTemplate(transactions).executeWithoutResult(status -> {
                deletion.deleteAllUserData(owner);
                parties.flush();   // its statements in the database now, as they are by its commit
                ask.add(guest.submit(() -> commands.saveOrVote(SongRequestEntity.builder().partyCode(partyCode)
                        .songName("Wilki - Baśka").style("ANY").decision("accepted").requestedAt(Instant.now()).build(), Set.of())));
                awaitLockWaiters(jdbc, 1);
            });
            assertThatThrownBy(() -> ask.getFirst().get(30, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                    .cause().isInstanceOf(IllegalArgumentException.class);
        } finally {
            guest.shutdownNow();
        }

        assertThat(count("song_requests", partyCode)).as("the guest's words are not kept").isZero();
    }

    /**
     * A busy party's account goes in a handful of statements: its requests and the DJ's feedback each in one {@code DELETE}, not read
     * and deleted one row at a time (300 requests made 300 statements more).
     */
    @Test
    void theRequests_goInOneStatement() {
        String owner = "deleted-" + System.nanoTime();
        String partyCode = party(owner).getPartyCode();
        List<SongRequestEntity> many = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            many.add(SongRequestEntity.builder().partyCode(partyCode).songName("Song " + i).style("ANY").decision("played")
                    .requestedAt(Instant.now()).build());
        }
        requests.saveAll(many);
        for (int i = 0; i < 5; i++) {
            feedback.save(FeedbackEntity.builder().partyCode(partyCode).ownerId(owner).message("Super " + i)
                    .submittedAt(Instant.now()).build());
        }
        Statistics statistics = entityManagers.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();

        deletion.deleteAllUserData(owner);

        assertThat(statistics.getPrepareStatementCount()).as("statements of the deletion").isLessThan(20);
        assertThat(count("song_requests", partyCode)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM feedback WHERE owner_id = ?", Integer.class, owner)).isZero();
    }
}
