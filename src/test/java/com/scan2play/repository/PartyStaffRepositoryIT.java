package com.scan2play.repository;

import com.scan2play.PostgresIntegrationTest;
import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.entity.PartyStaffEntity;
import com.scan2play.service.PartyStaffService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.scan2play.model.StaffPermission;
import com.scan2play.model.StaffRole;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The party's staff on a real PostgreSQL (V30, V32, V33): one row per person and party (the unique key), the owner removes only a row
 * of their own party, a person's rows go with their account and a party's with the party (the foreign key), an invitation link joins
 * once — and people joining at the same moment never pass the limit (the party row's lock) —, what each may do is kept, the link
 * gives its role, and a link renewed while someone joins by the old one joins nobody.
 */
class PartyStaffRepositoryIT extends PostgresIntegrationTest {

    @Autowired PartyStaffRepository staff;
    @Autowired PartySettingsRepository parties;
    @Autowired PartyStaffService service;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;

    private PartySettingsEntity party(String owner) {
        return parties.saveAndFlush(PartySettingsEntity.builder().partyCode(newPartyCode()).ownerId(owner + "-" + System.nanoTime())
                .active(true).build());
    }

    private PartyStaffEntity member(String partyCode, String memberId) {
        return staff.saveAndFlush(PartyStaffEntity.builder().partyCode(partyCode).memberId(memberId).memberName(memberId)
                .joinedAt(Instant.now()).build());
    }

    @Test
    void aPersonIsOnAPartysStaffOnce() {
        PartySettingsEntity pub = party("pub");
        member(pub.getPartyCode(), "kasia");

        assertThatThrownBy(() -> member(pub.getPartyCode(), "kasia")).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(staff.existsByPartyCodeAndMemberId(pub.getPartyCode(), "kasia")).isTrue();
        assertThat(staff.existsByPartyCodeAndMemberId(pub.getPartyCode(), "ola")).isFalse();
    }

    @Test
    void theOwnerRemovesOnlyARowOfTheirOwnParty() {
        PartySettingsEntity pub = party("pub");
        PartySettingsEntity other = party("other");
        PartyStaffEntity kasia = member(pub.getPartyCode(), "kasia");
        PartyStaffEntity elsewhere = member(other.getPartyCode(), "kasia");

        assertThat(staff.deleteMember(pub.getPartyCode(), "nobody")).as("leaving a staff one is not on").isZero();
        assertThat(staff.deleteFromParty(elsewhere.getId(), pub.getPartyCode())).as("a row of another party").isZero();
        assertThat(staff.deleteFromParty(kasia.getId(), pub.getPartyCode())).isOne();
        assertThat(staff.existsByPartyCodeAndMemberId(other.getPartyCode(), "kasia")).isTrue();
    }

    @Test
    void aPersonsRowsGoWithTheirAccount_andAPartysWithTheParty() {
        PartySettingsEntity pub = party("pub");
        PartySettingsEntity club = party("club");
        String kasia = "kasia-" + System.nanoTime();   // the other tests' rows stay in the class's database
        member(pub.getPartyCode(), kasia);
        member(club.getPartyCode(), kasia);
        member(pub.getPartyCode(), "ola");

        assertThat(staff.deleteByMember(kasia)).isEqualTo(2);
        parties.delete(pub);
        parties.flush();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM party_staff WHERE party_code = ?", Integer.class, pub.getPartyCode()))
                .as("the party's staff went with it (ON DELETE CASCADE)").isZero();
    }

    @Test
    void theInvitationLink_joinsOnce_andTheStaffIsListedInTheOrderTheyJoined() {
        PartySettingsEntity pub = party("pub");
        pub.setStaffToken("it-token-" + System.nanoTime());
        parties.saveAndFlush(pub);

        String kasia = "kasia-" + System.nanoTime();   // the other tests' rows stay in the class's database
        assertThat(service.join(pub.getStaffToken(), kasia, "Kasia").outcome()).isEqualTo(PartyStaffService.JoinOutcome.JOINED);
        assertThat(service.join(pub.getStaffToken(), kasia, "Kasia").outcome()).isEqualTo(PartyStaffService.JoinOutcome.ALREADY);
        assertThat(service.join(pub.getStaffToken(), "ola", null).outcome()).isEqualTo(PartyStaffService.JoinOutcome.JOINED);

        assertThat(service.staffOf(pub.getPartyCode())).extracting(PartyStaffEntity::getMemberId).containsExactly(kasia, "ola");
        assertThat(service.partiesOf(kasia)).extracting(PartySettingsEntity::getPartyCode).containsExactly(pub.getPartyCode());
    }

    /** What a person may do (V32): a joiner starts with the role "Obsługa kolejki"; the owner changes it, only on their own party. */
    @Test
    void thePermissions_areKept_andChangedOnlyOnTheOwnersParty() {
        PartySettingsEntity pub = party("pub");
        PartySettingsEntity other = party("other");
        pub.setStaffToken("it-perm-" + System.nanoTime());
        parties.saveAndFlush(pub);
        String kasia = "kasia-" + System.nanoTime();
        service.join(pub.getStaffToken(), kasia, "Kasia");
        long id = staff.findByPartyCodeAndMemberId(pub.getPartyCode(), kasia).orElseThrow().getId();

        assertThat(service.accessOf(pub, kasia).orElseThrow().permissions()).isEqualTo(StaffRole.QUEUE.permissions());
        assertThat(jdbc.queryForObject("SELECT permissions FROM party_staff WHERE id = ?", String.class, id))
                .isEqualTo("QUEUE,TIPS,CLEAR_QUEUE,OPEN_CLOSE,HISTORY");

        assertThat(service.setPermissions(other.getPartyCode(), id, StaffPermission.all())).as("another party's owner").isFalse();
        assertThat(service.setPermissions(pub.getPartyCode(), id, EnumSet.of(StaffPermission.HISTORY, StaffPermission.SUMMARY))).isTrue();
        assertThat(service.accessOf(pub, kasia).orElseThrow().permissions()).containsExactly(StaffPermission.HISTORY, StaffPermission.SUMMARY);
        assertThat(service.setPermissions(pub.getPartyCode(), id, EnumSet.noneOf(StaffPermission.class))).isTrue();
        assertThat(jdbc.queryForObject("SELECT permissions FROM party_staff WHERE id = ?", String.class, id)).isEmpty();
    }

    /** The link gives the role it was made with (V33), kept as the names in SQL too. */
    @Test
    void theLink_givesTheRoleItWasMadeWith() {
        PartySettingsEntity pub = party("pub");
        pub.setStaffToken("it-role-" + System.nanoTime());
        pub.setStaffLinkPermissions(StaffRole.VIEWER.permissions());
        parties.saveAndFlush(pub);
        assertThat(jdbc.queryForObject("SELECT staff_link_permissions FROM party_settings WHERE party_code = ?", String.class,
                pub.getPartyCode())).isEqualTo("HISTORY");

        String kasia = "kasia-" + System.nanoTime();
        assertThat(service.join(pub.getStaffToken(), kasia, "Kasia").outcome()).isEqualTo(PartyStaffService.JoinOutcome.JOINED);
        assertThat(service.accessOf(pub, kasia).orElseThrow().permissions()).containsExactly(StaffPermission.HISTORY);
    }

    /**
     * The organiser makes a new link (another role) while someone joins by the old one: the join waits for the organiser's
     * transaction (the row's lock, read by the token) and PostgreSQL then sees the token changed — the old link joins nobody. Seen
     * red when the link was read before the lock: the old link joined, with the role read before the change.
     */
    @Test
    void aLinkRenewedWhileSomeoneJoins_theOldOneJoinsNobody() throws Exception {
        PartySettingsEntity pub = party("pub");
        String oldToken = "it-old-" + System.nanoTime();
        pub.setStaffToken(oldToken);
        pub.setStaffLinkPermissions(StaffRole.CO_ORGANISER.permissions());
        parties.saveAndFlush(pub);
        String kasia = "kasia-" + System.nanoTime();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch renewed = new CountDownLatch(1);
        CountDownLatch joinStarted = new CountDownLatch(1);
        try {
            Future<?> organiser = pool.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                jdbc.update("UPDATE party_settings SET staff_token = ?, staff_link_permissions = 'HISTORY' WHERE party_code = ?",
                        "it-new-" + System.nanoTime(), pub.getPartyCode());
                renewed.countDown();
                try {
                    joinStarted.await(5, TimeUnit.SECONDS);
                    Thread.sleep(700);   // the join is waiting for this row by now
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
            renewed.await(5, TimeUnit.SECONDS);
            Future<PartyStaffService.JoinOutcome> joining = pool.submit(() -> {
                joinStarted.countDown();
                return service.join(oldToken, kasia, "Kasia").outcome();
            });
            organiser.get(10, TimeUnit.SECONDS);

            assertThat(joining.get(10, TimeUnit.SECONDS)).isEqualTo(PartyStaffService.JoinOutcome.UNKNOWN_LINK);
            assertThat(staff.existsByPartyCodeAndMemberId(pub.getPartyCode(), kasia)).isFalse();
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * Twenty people open the link at the same moment: ten join, the others hear the staff is full — counted one after another under
     * the party row's lock (before V32 it was "count, then insert": two at once could make eleven). One person in two tabs at once:
     * one row, no error.
     */
    @Test
    void peopleJoiningAtOnce_neverPassTheLimit() throws Exception {
        PartySettingsEntity pub = party("pub");
        pub.setStaffToken("it-rush-" + System.nanoTime());
        parties.saveAndFlush(pub);
        String prefix = "rush-" + System.nanoTime() + "-";

        int people = 20;
        ExecutorService pool = Executors.newFixedThreadPool(people);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<PartyStaffService.JoinOutcome>> outcomes = new ArrayList<>();
        try {
            for (int i = 0; i < people; i++) {
                String who = prefix + (i < 2 ? "twotabs" : i);   // the first two: one person in two tabs
                Callable<PartyStaffService.JoinOutcome> join = () -> {
                    start.await();
                    return service.join(pub.getStaffToken(), who, who).outcome();
                };
                outcomes.add(pool.submit(join));
            }
            start.countDown();
            List<PartyStaffService.JoinOutcome> results = new ArrayList<>();
            for (Future<PartyStaffService.JoinOutcome> outcome : outcomes) {
                results.add(outcome.get());
            }

            assertThat(staff.countByPartyCode(pub.getPartyCode())).isEqualTo(PartyStaffService.MAX_STAFF);
            assertThat(results).filteredOn(r -> r == PartyStaffService.JoinOutcome.JOINED).hasSize(PartyStaffService.MAX_STAFF);
            assertThat(results).doesNotContainNull()
                    .allMatch(r -> r == PartyStaffService.JoinOutcome.JOINED || r == PartyStaffService.JoinOutcome.FULL
                            || r == PartyStaffService.JoinOutcome.ALREADY);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM party_staff WHERE member_id = ?", Integer.class, prefix + "twotabs"))
                    .isLessThanOrEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }
}
