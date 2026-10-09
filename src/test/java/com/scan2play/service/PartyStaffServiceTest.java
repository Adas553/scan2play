package com.scan2play.service;

import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.entity.PartyStaffEntity;
import com.scan2play.repository.PartySettingsRepository;
import com.scan2play.repository.PartyStaffRepository;
import com.scan2play.service.PartyStaffService.JoinOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The party's staff (V30): who may open a party, what an invitation link does, the panels to switch between. */
class PartyStaffServiceTest {

    private static final String PARTY = "ABC12";
    private static final String TOKEN = "invite-token";

    private PartyStaffRepository staff;
    private PartySettingsRepository parties;
    private PartySettingsQueryService query;
    private PartyStaffService service;
    private PartySettingsEntity party;

    @BeforeEach
    void setUp() {
        staff = mock(PartyStaffRepository.class);
        parties = mock(PartySettingsRepository.class);
        query = mock(PartySettingsQueryService.class);
        service = new PartyStaffService(staff, parties, query);
        party = PartySettingsEntity.builder().partyCode(PARTY).ownerId("owner").djName("DJ Koko").staffToken(TOKEN).build();
        when(parties.findByStaffToken(TOKEN)).thenReturn(Optional.of(party));
    }

    @Test
    void theOwner_hasAccessWithoutAQuery_aStaffMemberByTheirRow_anyoneElseNot() {
        when(staff.existsByPartyCodeAndMemberId(PARTY, "kasia")).thenReturn(true);

        assertThat(service.hasAccess(party, "owner")).isTrue();
        verify(staff, never()).existsByPartyCodeAndMemberId(PARTY, "owner");
        assertThat(service.hasAccess(party, "kasia")).isTrue();
        assertThat(service.hasAccess(party, "stranger")).isFalse();
        assertThat(PartyStaffService.isOwner(PartySettingsEntity.builder().partyCode(PARTY).build(), "owner"))
                .as("a party without an owner is nobody's").isFalse();
    }

    @Test
    void theLink_joinsThePerson_withTheNameOfTheirAccount() {
        JoinOutcome outcome = service.join(TOKEN, "kasia", "  Kasia   Nowak ").outcome();

        assertThat(outcome).isEqualTo(JoinOutcome.JOINED);
        ArgumentCaptor<PartyStaffEntity> saved = ArgumentCaptor.forClass(PartyStaffEntity.class);
        verify(staff).save(saved.capture());
        assertThat(saved.getValue().getPartyCode()).isEqualTo(PARTY);
        assertThat(saved.getValue().getMemberId()).isEqualTo("kasia");
        assertThat(saved.getValue().getMemberName()).isEqualTo("Kasia Nowak");
        assertThat(saved.getValue().getJoinedAt()).isNotNull();
    }

    @Test
    void anOldOrUnknownLink_theOwnersOwnLink_aSecondJoin_andAFullStaff_saveNothing() {
        when(staff.existsByPartyCodeAndMemberId(PARTY, "kasia")).thenReturn(true);
        when(staff.countByPartyCode(PARTY)).thenReturn((long) PartyStaffService.MAX_STAFF);

        assertThat(service.join("old-token", "kasia", "Kasia").outcome()).isEqualTo(JoinOutcome.UNKNOWN_LINK);
        assertThat(service.join("x".repeat(40), "kasia", "Kasia").outcome()).isEqualTo(JoinOutcome.UNKNOWN_LINK);
        assertThat(service.join(TOKEN, "owner", "Owner").outcome()).isEqualTo(JoinOutcome.OWNER);
        assertThat(service.join(TOKEN, "kasia", "Kasia").outcome()).isEqualTo(JoinOutcome.ALREADY);
        PartyStaffService.Joined full = service.join(TOKEN, "eleventh", "Ola");
        assertThat(full.outcome()).isEqualTo(JoinOutcome.FULL);
        assertThat(full.party()).as("a full staff opens nothing").isNull();
        verify(staff, never()).save(any());
    }

    @Test
    void twoTabsJoiningAtOnce_areOneJoin() {
        when(staff.save(any())).thenThrow(new DataIntegrityViolationException("uk_party_staff_member"));

        assertThat(service.join(TOKEN, "kasia", null).outcome()).isEqualTo(JoinOutcome.ALREADY);
    }

    @Test
    void thePanels_areTheirOwnFirst_thenThePartiesTheyWorkAt_namedByWhoPlaysOrTheCode() {
        when(staff.findTop20ByMemberIdOrderByJoinedAtAscIdAsc("kasia")).thenReturn(List.of(
                PartyStaffEntity.builder().partyCode(PARTY).memberId("kasia").joinedAt(Instant.now()).build(),
                PartyStaffEntity.builder().partyCode("PUB01").memberId("kasia").joinedAt(Instant.now()).build(),
                PartyStaffEntity.builder().partyCode("GONE1").memberId("kasia").joinedAt(Instant.now()).build()));
        when(query.getSettings(PARTY)).thenReturn(party);
        when(query.getSettings("PUB01")).thenReturn(PartySettingsEntity.builder().partyCode("PUB01").ownerId("pub").build());
        when(query.getSettings("GONE1")).thenThrow(new IllegalArgumentException("deleted"));

        assertThat(service.panelsOf("kasia")).containsExactly(new PartyStaffService.Panel(null, null, true),
                new PartyStaffService.Panel(PARTY, "DJ Koko", false), new PartyStaffService.Panel("PUB01", "PUB01", false));
        // no party of their own yet (a bartender): "Mój panel" all the same — it makes one (2026-10-09: without it they never could)
        assertThat(service.panelsOf("ola")).containsExactly(new PartyStaffService.Panel(null, null, true));
    }

    @Test
    void theOwnerRemovesOnlyARowOfTheirOwnParty() {
        when(staff.deleteFromParty(7L, PARTY)).thenReturn(1);

        assertThat(service.remove(PARTY, 7L)).isTrue();
        assertThat(service.remove(PARTY, 8L)).as("a row of another party: not deleted").isFalse();
    }
}
