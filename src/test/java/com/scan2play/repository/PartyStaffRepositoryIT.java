package com.scan2play.repository;

import com.scan2play.PostgresIntegrationTest;
import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.entity.PartyStaffEntity;
import com.scan2play.service.PartyStaffService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The party's staff on a real PostgreSQL (V30): one row per person and party (the unique key), the owner removes only a row of
 * their own party, a person's rows go with their account and a party's with the party (the foreign key), and an invitation link
 * joins once.
 */
class PartyStaffRepositoryIT extends PostgresIntegrationTest {

    @Autowired PartyStaffRepository staff;
    @Autowired PartySettingsRepository parties;
    @Autowired PartyStaffService service;
    @Autowired JdbcTemplate jdbc;

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
}
