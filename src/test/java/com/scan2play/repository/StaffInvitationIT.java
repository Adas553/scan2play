package com.scan2play.repository;

import com.scan2play.PostgresIntegrationTest;
import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.model.StaffPermission;
import com.scan2play.model.StaffRole;
import com.scan2play.service.PartyStaffService;
import com.scan2play.service.StaffInvitationService;
import com.scan2play.service.StaffInvitationService.AnswerOutcome;
import com.scan2play.service.StaffInvitationService.InviteOutcome;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Invitations to a party's staff by e-mail on a real PostgreSQL (V34): one invitation answered in two places at once joins once; the
 * staff and the invitations never take more than 10 places, however many are made and joined at once (the party row's lock); an
 * address is one invitation per party (Gmail's dots too); an invitation past its 30 days counts for nothing and the night's cleanup
 * deletes it; a party's invitations go with it.
 */
class StaffInvitationIT extends PostgresIntegrationTest {

    @Autowired StaffInvitationService invitations;
    @Autowired StaffInvitationRepository rows;
    @Autowired PartyStaffService staff;
    @Autowired PartyStaffRepository staffRows;
    @Autowired PartySettingsRepository parties;
    @Autowired JdbcTemplate jdbc;

    private PartySettingsEntity party(String owner) {
        return parties.saveAndFlush(PartySettingsEntity.builder().partyCode(newPartyCode()).ownerId(owner + "-" + System.nanoTime())
                .active(true).build());
    }

    private static String address(String who) {
        return who + "." + System.nanoTime() + "@gmail.com";
    }

    @Test
    void anInvitation_joinsWithItsRole_andGoes() {
        PartySettingsEntity pub = party("pub");
        String kasia = address("kasia");
        assertThat(invitations.invite(pub.getPartyCode(), kasia, StaffRole.VIEWER.permissions())).isEqualTo(InviteOutcome.INVITED);

        // Google says the account's address as it is, without the dot the organiser typed: the same Gmail account
        StaffInvitationService.Waiting waiting = invitations.waitingFor(kasia.replaceFirst("[.]", "")).orElseThrow();
        assertThat(waiting.party().getPartyCode()).isEqualTo(pub.getPartyCode());
        String member = "kasia-" + System.nanoTime();
        assertThat(invitations.accept(waiting.invitation().getId(), kasia, member, "Kasia").outcome()).isEqualTo(AnswerOutcome.JOINED);

        assertThat(staff.accessOf(pub, member).orElseThrow().permissions()).containsExactly(StaffPermission.HISTORY);
        assertThat(rows.findById(waiting.invitation().getId())).as("answered: gone, the address with it").isEmpty();
        assertThat(invitations.waitingFor(kasia)).isEmpty();
    }

    /**
     * Two "Dołącz" on one invitation at the same moment (two tabs, the app and a browser): one row on the staff, no error, the
     * invitation gone. Seen red without the party row's lock: both inserted and one hit the unique key.
     */
    @Test
    void oneInvitation_answeredTwiceAtOnce_joinsOnce() throws Exception {
        for (int round = 0; round < 10; round++) {
            PartySettingsEntity pub = party("pub");
            String kasia = address("kasia");
            invitations.invite(pub.getPartyCode(), kasia, StaffRole.QUEUE.permissions());
            long id = invitations.waitingAt(pub.getPartyCode()).getFirst().getId();
            String member = "kasia-" + System.nanoTime();

            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch start = new CountDownLatch(1);
            try {
                List<Future<AnswerOutcome>> answers = new ArrayList<>();
                for (int i = 0; i < 2; i++) {
                    Callable<AnswerOutcome> answer = () -> {
                        start.await();
                        return invitations.accept(id, kasia, member, "Kasia").outcome();
                    };
                    answers.add(pool.submit(answer));
                }
                start.countDown();
                List<AnswerOutcome> outcomes = new ArrayList<>();
                for (Future<AnswerOutcome> answer : answers) {
                    outcomes.add(answer.get());
                }
                assertThat(outcomes).contains(AnswerOutcome.JOINED)
                        .allMatch(o -> o == AnswerOutcome.JOINED || o == AnswerOutcome.GONE || o == AnswerOutcome.ALREADY);
                assertThat(jdbc.queryForObject("SELECT count(*) FROM party_staff WHERE member_id = ?", Integer.class, member)).isOne();
                assertThat(rows.findById(id)).isEmpty();
            } finally {
                pool.shutdownNow();
            }
        }
    }

    /**
     * Eight people on the staff; at the same moment the organiser invites five addresses and five people join by the link: never more
     * than 10 places taken — the invitations and the joins counted one after another under the party row's lock.
     */
    @Test
    void invitationsAndJoinsAtOnce_neverPassTheTenPlaces() throws Exception {
        PartySettingsEntity pub = party("pub");
        pub.setStaffToken("it-places-" + System.nanoTime());
        parties.saveAndFlush(pub);
        String prefix = "places-" + System.nanoTime() + "-";
        for (int i = 0; i < 8; i++) {
            staff.join(pub.getStaffToken(), prefix + "early" + i, "Early");
        }

        ExecutorService pool = Executors.newFixedThreadPool(10);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<?>> tasks = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                String who = prefix + i;
                tasks.add(pool.submit(() -> {
                    start.await();
                    return invitations.invite(pub.getPartyCode(), address("invited" + who), StaffRole.QUEUE.permissions());
                }));
                tasks.add(pool.submit(() -> {
                    start.await();
                    return staff.join(pub.getStaffToken(), who, "Late").outcome();
                }));
            }
            start.countDown();
            for (Future<?> task : tasks) {
                task.get();
            }

            assertThat(staff.placesTaken(pub.getPartyCode())).isEqualTo(PartyStaffService.MAX_STAFF);
            assertThat(staffRows.countByPartyCode(pub.getPartyCode()) + invitations.waitingAt(pub.getPartyCode()).size())
                    .isEqualTo(PartyStaffService.MAX_STAFF);
            assertThat(invitations.invite(pub.getPartyCode(), address("one-more"), StaffRole.QUEUE.permissions()))
                    .isEqualTo(InviteOutcome.FULL);
        } finally {
            pool.shutdownNow();
        }
    }

    /** One invitation per address and party — "Ola.Kowalska@" and "olakowalska@" are one Gmail account: the second changes the first. */
    @Test
    void anAddress_isOneInvitationPerParty() {
        PartySettingsEntity pub = party("pub");
        long n = System.nanoTime();
        String name = "ola" + n;
        invitations.invite(pub.getPartyCode(), "Ola." + n + "@gmail.com", StaffRole.QUEUE.permissions());

        assertThat(invitations.invite(pub.getPartyCode(), name + "@googlemail.com", StaffRole.CO_ORGANISER.permissions()))
                .isEqualTo(InviteOutcome.RENEWED);
        assertThat(invitations.waitingAt(pub.getPartyCode())).singleElement()
                .satisfies(i -> assertThat(i.getPermissions()).isEqualTo(StaffPermission.all()))
                .satisfies(i -> assertThat(i.getEmail()).isEqualTo(name + "@googlemail.com"));
    }

    /** Past its 30 days: not asked, not answered, not counted; the night deletes it; the same address may be invited again. */
    @Test
    void anOldInvitation_countsForNothing_andTheNightDeletesIt() {
        PartySettingsEntity pub = party("pub");
        String kasia = address("kasia");
        invitations.invite(pub.getPartyCode(), kasia, StaffRole.QUEUE.permissions());
        long id = invitations.waitingAt(pub.getPartyCode()).getFirst().getId();
        jdbc.update("UPDATE staff_invitation SET invited_at = now() - interval '31 days' WHERE id = ?", id);

        assertThat(invitations.waitingFor(kasia)).isEmpty();
        assertThat(invitations.waitingAt(pub.getPartyCode())).isEmpty();
        assertThat(staff.placesTaken(pub.getPartyCode())).isZero();
        assertThat(invitations.accept(id, kasia, "kasia-" + System.nanoTime(), "Kasia").outcome()).isEqualTo(AnswerOutcome.GONE);

        invitations.purgeOldInvitations();
        assertThat(rows.findById(id)).isEmpty();
        assertThat(invitations.invite(pub.getPartyCode(), kasia, StaffRole.QUEUE.permissions())).isEqualTo(InviteOutcome.INVITED);
    }

    /** Declined: gone from the organiser's list; another address cannot decline it; a party's invitations go with the party. */
    @Test
    void declining_isTheInvitedPersons_andAPartysInvitationsGoWithIt() {
        PartySettingsEntity pub = party("pub");
        String kasia = address("kasia");
        invitations.invite(pub.getPartyCode(), kasia, StaffRole.QUEUE.permissions());
        invitations.invite(pub.getPartyCode(), address("ola"), StaffRole.QUEUE.permissions());
        long id = invitations.waitingFor(kasia).orElseThrow().invitation().getId();

        assertThat(invitations.decline(id, address("someone"))).isFalse();
        assertThat(invitations.decline(id, kasia)).isTrue();
        assertThat(invitations.waitingAt(pub.getPartyCode())).hasSize(1);

        parties.delete(pub);
        parties.flush();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM staff_invitation WHERE party_code = ?", Integer.class, pub.getPartyCode()))
                .as("ON DELETE CASCADE").isZero();
    }
}
