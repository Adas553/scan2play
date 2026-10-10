package com.scan2play.service;

import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.entity.PartyStaffEntity;
import com.scan2play.model.StaffPermission;
import com.scan2play.model.StaffRole;
import com.scan2play.repository.PartySettingsRepository;
import com.scan2play.repository.PartyStaffRepository;
import com.scan2play.repository.StaffInvitationRepository;
import com.scan2play.service.PartyStaffService.JoinOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The party's staff (V30, V32, V33): who may open a party and do what there, what an invitation link does — with the role it was made
 * with —, the places the staff and the invitations by e-mail share, the panels to switch between.
 */
class PartyStaffServiceTest {

    private static final String PARTY = "ABC12";
    private static final String TOKEN = "invite-token";

    private PartyStaffRepository staff;
    private StaffInvitationRepository invitations;
    private PartySettingsRepository parties;
    private PartySettingsQueryService query;
    private PartyStaffService service;
    private PartySettingsEntity party;

    @BeforeEach
    void setUp() {
        staff = mock(PartyStaffRepository.class);
        parties = mock(PartySettingsRepository.class);
        query = mock(PartySettingsQueryService.class);
        invitations = mock(StaffInvitationRepository.class);
        service = new PartyStaffService(staff, invitations, parties, query);
        party = PartySettingsEntity.builder().partyCode(PARTY).ownerId("owner").djName("DJ Koko").staffToken(TOKEN).build();
        when(parties.findByStaffToken(TOKEN)).thenReturn(Optional.of(party));
        when(parties.lockByStaffToken(TOKEN)).thenReturn(Optional.of(party));
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
        assertThat(saved.getValue().getPermissions()).as("a link made before V33: what the staff could do before V32")
                .isEqualTo(StaffRole.QUEUE.permissions());
    }

    /** The link gives the role it was made with (V33): "Podgląd", or permissions ticked one by one — none ticked too. */
    @Test
    void theLink_givesTheRoleItWasMadeWith() {
        party.setStaffLinkPermissions(EnumSet.of(StaffPermission.HISTORY, StaffPermission.SUMMARY));
        service.join(TOKEN, "kasia", "Kasia");
        party.setStaffLinkPermissions(EnumSet.noneOf(StaffPermission.class));
        service.join(TOKEN, "ola", "Ola");

        ArgumentCaptor<PartyStaffEntity> saved = ArgumentCaptor.forClass(PartyStaffEntity.class);
        verify(staff, org.mockito.Mockito.times(2)).save(saved.capture());
        assertThat(saved.getAllValues().get(0).getPermissions()).containsExactly(StaffPermission.HISTORY, StaffPermission.SUMMARY);
        assertThat(saved.getAllValues().get(1).getPermissions()).isEmpty();
    }

    /**
     * The link is read with the party row locked, and counted under that lock: people joining at once never pass the limit, and a new
     * link made at that moment is waited for ({@code PartyStaffRepositoryIT} on PostgreSQL).
     */
    @Test
    void joining_locksThePartyByItsLink_beforeItCounts() {
        service.join(TOKEN, "kasia", "Kasia");

        InOrder order = inOrder(parties, staff);
        order.verify(parties).lockByStaffToken(TOKEN);
        order.verify(staff).countByPartyCode(PARTY);
        order.verify(staff).save(any());
        verify(parties, never()).findByStaffToken(any());
    }

    /** The staff and the invitations by e-mail waiting share the 10 places (V34): nine people and one invitation — the link is full. */
    @Test
    void theInvitationsWaiting_takePlacesToo() {
        when(staff.countByPartyCode(PARTY)).thenReturn((long) PartyStaffService.MAX_STAFF - 1);
        when(invitations.countByPartyCodeAndInvitedAtAfter(org.mockito.ArgumentMatchers.eq(PARTY), any())).thenReturn(1L);

        assertThat(service.placesTaken(PARTY)).isEqualTo(PartyStaffService.MAX_STAFF);
        assertThat(service.join(TOKEN, "kasia", "Kasia").outcome()).isEqualTo(JoinOutcome.FULL);
        // answering one of those invitations: its own place is already counted
        assertThat(service.addToStaff(party, "ola", "Ola", StaffRole.VIEWER.permissions(), true)).isEqualTo(JoinOutcome.JOINED);
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
        assertThat(full.party()).as("named on the page that says it is full").isSameAs(party);
        verify(staff, never()).save(any());
    }

    @Test
    void thePanels_areTheirOwnFirst_byItsName_thenThePartiesTheyWorkAt_namedByWhoPlaysTheOrganiserOrTheCode() {
        when(staff.findTop20ByMemberIdOrderByJoinedAtAscIdAsc("kasia")).thenReturn(List.of(
                PartyStaffEntity.builder().partyCode(PARTY).memberId("kasia").joinedAt(Instant.now()).build(),
                PartyStaffEntity.builder().partyCode("PUB01").memberId("kasia").joinedAt(Instant.now()).build(),
                PartyStaffEntity.builder().partyCode("BAR01").memberId("kasia").joinedAt(Instant.now()).build(),
                PartyStaffEntity.builder().partyCode("GONE1").memberId("kasia").joinedAt(Instant.now()).build()));
        when(query.getSettings(PARTY)).thenReturn(party);
        when(query.getSettings("PUB01")).thenReturn(PartySettingsEntity.builder().partyCode("PUB01").ownerId("pub").build());
        when(query.getSettings("BAR01")).thenReturn(PartySettingsEntity.builder().partyCode("BAR01").ownerId("bar")
                .ownerName("Ola Kowalska").build());
        when(query.getSettings("GONE1")).thenThrow(new IllegalArgumentException("deleted"));
        when(parties.findByOwnerId("kasia")).thenReturn(Optional.of(PartySettingsEntity.builder().partyCode("KAS01")
                .ownerId("kasia").djName("DJ Kasia").build()));

        assertThat(service.panelsOf("kasia")).containsExactly(new PartyStaffService.Panel("KAS01", "DJ Kasia", true),
                new PartyStaffService.Panel(PARTY, "DJ Koko", false), new PartyStaffService.Panel("PUB01", "PUB01", false),
                new PartyStaffService.Panel("BAR01", "Ola Kowalska", false));
        // no party of their own (a bartender): nothing that would make one by a click — "Załóż własną imprezę" does it on purpose
        when(parties.findByOwnerId("ola")).thenReturn(Optional.empty());
        assertThat(service.panelsOf("ola")).isEmpty();
    }

    /** What a person may do: the owner everything without a query, a person of the staff what their row says, anyone else nothing. */
    @Test
    void theAccess_isTheOwnersAll_theStaffsRow_orNothing() {
        when(staff.findByPartyCodeAndMemberId(PARTY, "kasia")).thenReturn(Optional.of(PartyStaffEntity.builder().partyCode(PARTY)
                .memberId("kasia").permissions(EnumSet.of(StaffPermission.HISTORY)).build()));

        PartyStaffService.Access owner = service.accessOf(party, "owner").orElseThrow();
        assertThat(owner.owner()).isTrue();
        assertThat(owner.may(StaffPermission.SUMMARY)).isTrue();
        assertThat(owner.role()).isNull();
        verify(staff, never()).findByPartyCodeAndMemberId(PARTY, "owner");

        PartyStaffService.Access kasia = service.accessOf(party, "kasia").orElseThrow();
        assertThat(kasia.owner()).isFalse();
        assertThat(kasia.may(StaffPermission.HISTORY)).isTrue();
        assertThat(kasia.may("QUEUE")).isFalse();
        assertThat(kasia.maySomeSettings()).isFalse();
        assertThat(kasia.role()).isEqualTo(StaffRole.VIEWER);

        assertThat(service.accessOf(party, "stranger")).isEmpty();
    }

    @Test
    void theOwnerChangesThePermissions_onlyOfARowOfTheirOwnParty() {
        PartyStaffEntity row = PartyStaffEntity.builder().id(7L).partyCode(PARTY).memberId("kasia")
                .permissions(StaffRole.QUEUE.permissions()).build();
        when(staff.findById(7L)).thenReturn(Optional.of(row));
        when(staff.findById(8L)).thenReturn(Optional.of(PartyStaffEntity.builder().id(8L).partyCode("OTHER").memberId("x")
                .permissions(StaffRole.QUEUE.permissions()).build()));

        assertThat(service.setPermissions(PARTY, 7L, StaffRole.CO_ORGANISER.permissions())).isTrue();
        assertThat(row.getPermissions()).isEqualTo(StaffPermission.all());
        assertThat(service.setPermissions(PARTY, 7L, EnumSet.noneOf(StaffPermission.class))).isTrue();
        assertThat(row.getPermissions()).isEmpty();
        assertThat(service.setPermissions(PARTY, 8L, StaffPermission.all())).as("a row of another party").isFalse();
        assertThat(service.setPermissions(PARTY, 9L, StaffPermission.all())).as("no such row").isFalse();
    }

    @Test
    void aParty_isNamedByWhoPlays_elseItsOrganiser_elseItsCode() {
        assertThat(PartyStaffService.nameOf(party)).isEqualTo("DJ Koko");
        assertThat(PartyStaffService.nameOf(PartySettingsEntity.builder().partyCode("X1").ownerName("Ola").build())).isEqualTo("Ola");
        assertThat(PartyStaffService.nameOf(PartySettingsEntity.builder().partyCode("X1").build())).isEqualTo("X1");
    }

    @Test
    void aPersonLeavesTheStaff_onlyTheirOwnRow() {
        when(staff.deleteMember(PARTY, "kasia")).thenReturn(1);

        assertThat(service.leave(PARTY, "kasia")).isTrue();
        assertThat(service.leave(PARTY, "stranger")).as("not on the staff").isFalse();
    }

    @Test
    void theOwnerRemovesOnlyARowOfTheirOwnParty() {
        when(staff.deleteFromParty(7L, PARTY)).thenReturn(1);

        assertThat(service.remove(PARTY, 7L)).isTrue();
        assertThat(service.remove(PARTY, 8L)).as("a row of another party: not deleted").isFalse();
    }
}
