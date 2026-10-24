package com.scan2play.service;

import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.entity.StaffInvitationEntity;
import com.scan2play.model.StaffPermission;
import com.scan2play.model.StaffRole;
import com.scan2play.repository.PartySettingsRepository;
import com.scan2play.repository.StaffInvitationRepository;
import com.scan2play.service.StaffInvitationService.AnswerOutcome;
import com.scan2play.service.StaffInvitationService.InviteOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Invitations by e-mail (V34): the organiser's "Zaproś" (an address, a role, the places of 10), the login's match, "Dołącz" and
 * "Nie, dziękuję" — only for the verified address the invitation names. The races are {@code StaffInvitationIT}'s, on PostgreSQL.
 */
class StaffInvitationServiceTest {

    private static final String PARTY = "PUB01";

    private StaffInvitationRepository invitations;
    private PartySettingsRepository parties;
    private PartyStaffService staff;
    private PartySettingsQueryService query;
    private StaffInvitationService service;
    private final PartySettingsEntity pub = PartySettingsEntity.builder().partyCode(PARTY).ownerId("owner").build();

    @BeforeEach
    void setUp() {
        invitations = mock(StaffInvitationRepository.class);
        parties = mock(PartySettingsRepository.class);
        staff = mock(PartyStaffService.class);
        query = mock(PartySettingsQueryService.class);
        service = new StaffInvitationService(invitations, parties, staff, query);
        when(parties.lockByPartyCode(PARTY)).thenReturn(Optional.of(pub));
        lenient().when(staff.placesOf(pub)).thenReturn(com.scan2play.model.Plan.PRO.staffPlaces());   // the places of the organiser's plan (V35)
        when(query.getSettings(PARTY)).thenReturn(pub);
        when(invitations.findByPartyCodeAndEmailKey(anyString(), anyString())).thenReturn(Optional.empty());
    }

    private static StaffInvitationEntity invitation(long id, String key, Instant invitedAt) {
        return StaffInvitationEntity.builder().id(id).partyCode(PARTY).email(key).emailKey(key)
                .permissions(EnumSet.of(StaffPermission.HISTORY)).invitedAt(invitedAt).build();
    }

    @Test
    void zapros_keepsTheAddressAsTyped_matchesItAsGmailDelivers_andTheRole() {
        assertThat(service.invite(PARTY, " Ola.Kowalska@Gmail.com ", StaffRole.VIEWER.permissions())).isEqualTo(InviteOutcome.INVITED);

        ArgumentCaptor<StaffInvitationEntity> saved = ArgumentCaptor.forClass(StaffInvitationEntity.class);
        verify(invitations).save(saved.capture());
        assertThat(saved.getValue().getEmail()).isEqualTo("ola.kowalska@gmail.com");
        assertThat(saved.getValue().getEmailKey()).isEqualTo("olakowalska@gmail.com");
        assertThat(saved.getValue().getPermissions()).containsExactly(StaffPermission.HISTORY);
        assertThat(saved.getValue().getPartyCode()).isEqualTo(PARTY);
    }

    /** Counted under the party row's lock, as joining by the link: the places are never passed ({@code StaffInvitationIT}). */
    @Test
    void zapros_locksTheParty_beforeItCountsThePlaces() {
        service.invite(PARTY, "ola@gmail.com", StaffRole.QUEUE.permissions());

        InOrder order = inOrder(parties, staff, invitations);
        order.verify(parties).lockByPartyCode(PARTY);
        order.verify(staff).placesTaken(PARTY);
        order.verify(invitations).save(any());
    }

    @Test
    void zapros_aTypo_andNoPlaceLeft_saveNothing() {
        when(staff.placesTaken(PARTY)).thenReturn((long) com.scan2play.model.Plan.PRO.staffPlaces());

        assertThat(service.invite(PARTY, "ola@gmail", StaffRole.QUEUE.permissions())).isEqualTo(InviteOutcome.INVALID_ADDRESS);
        assertThat(service.invite(PARTY, "ola@gmail.com", StaffRole.QUEUE.permissions())).isEqualTo(InviteOutcome.FULL);
        verify(invitations, never()).save(any());
    }

    /**
     * The organiser types their own address (the one Google confirms for them): no invitation — it would take a place for 30 days and
     * join nobody ("Dołącz" on one's own party only deletes it). Gmail's dots and case do not hide it.
     */
    @Test
    void zapros_theOrganisersOwnAddress_savesNothing() {
        assertThat(service.invite(PARTY, "Adam.Lasut@gmail.com", StaffRole.QUEUE.permissions(), "adamlasut@gmail.com"))
                .isEqualTo(InviteOutcome.OWN_ADDRESS);
        verify(invitations, never()).save(any());
        assertThat(service.invite(PARTY, "ola@gmail.com", StaffRole.QUEUE.permissions(), "adamlasut@gmail.com"))
                .isEqualTo(InviteOutcome.INVITED);
    }

    /** The same address again: its role changed and 30 days again — no second row; a waiting one takes no new place. */
    @Test
    void zapros_theSameAddressAgain_changesItsInvitation() {
        StaffInvitationEntity earlier = invitation(3L, "olakowalska@gmail.com", Instant.now().minus(5, ChronoUnit.DAYS));
        when(invitations.findByPartyCodeAndEmailKey(PARTY, "olakowalska@gmail.com")).thenReturn(Optional.of(earlier));
        when(staff.placesTaken(PARTY)).thenReturn((long) com.scan2play.model.Plan.PRO.staffPlaces());

        assertThat(service.invite(PARTY, "ola.kowalska@gmail.com", StaffRole.CO_ORGANISER.permissions())).isEqualTo(InviteOutcome.RENEWED);
        verify(invitations).save(earlier);
        assertThat(earlier.getPermissions()).isEqualTo(StaffPermission.all());
        assertThat(earlier.getInvitedAt()).isAfter(Instant.now().minusSeconds(5));
    }

    @Test
    void theLogin_findsTheOldestInvitationWaitingForItsAddress_skippingAPartyGone() {
        StaffInvitationEntity gone = StaffInvitationEntity.builder().id(1L).partyCode("GONE1").emailKey("kasia@gmail.com").build();
        StaffInvitationEntity waiting = invitation(2L, "kasia@gmail.com", Instant.now());
        when(invitations.findTop10ByEmailKeyAndInvitedAtAfterOrderByInvitedAtAscIdAsc(eq("kasia@gmail.com"), any()))
                .thenReturn(List.of(gone, waiting));
        when(query.getSettings("GONE1")).thenThrow(new IllegalArgumentException("deleted"));

        Optional<StaffInvitationService.Waiting> found = service.waitingFor("Kasia@GoogleMail.com");
        assertThat(found).isPresent();
        assertThat(found.get().invitation()).isSameAs(waiting);
        assertThat(found.get().party()).isSameAs(pub);
        assertThat(service.waitingFor(null)).isEmpty();
        assertThat(service.waitingFor(" ")).isEmpty();
    }

    @Test
    void dolacz_joinsWithTheInvitationsRole_andTheInvitationGoes() {
        when(invitations.findById(3L)).thenReturn(Optional.of(invitation(3L, "kasia@gmail.com", Instant.now())));
        when(invitations.existsByIdAndEmailKeyAndInvitedAtAfter(eq(3L), eq("kasia@gmail.com"), any())).thenReturn(true);
        when(staff.addToStaff(pub, "kasia", "Kasia", EnumSet.of(StaffPermission.HISTORY), true)).thenReturn(PartyStaffService.JoinOutcome.JOINED);

        StaffInvitationService.Answer answer = service.accept(3L, "kasia@gmail.com", "kasia", "Kasia");

        assertThat(answer.outcome()).isEqualTo(AnswerOutcome.JOINED);
        assertThat(answer.party()).isSameAs(pub);
        InOrder order = inOrder(parties, invitations, staff);
        order.verify(parties).lockByPartyCode(PARTY);
        order.verify(invitations).existsByIdAndEmailKeyAndInvitedAtAfter(eq(3L), eq("kasia@gmail.com"), any());
        order.verify(staff).addToStaff(pub, "kasia", "Kasia", EnumSet.of(StaffPermission.HISTORY), true);
        order.verify(invitations).deleteForAddress(3L, "kasia@gmail.com");
    }

    /**
     * "Dołącz" when the staff is full (the organiser's plan went from Pro to Free: fewer places than people): the invitation goes too —
     * kept, it sent the person to "Dołącz" and a 409 on every opening of the panel for 30 days (the review of 2026-10-14).
     */
    @Test
    void dolacz_onAFullStaff_theInvitationGoes() {
        when(invitations.findById(3L)).thenReturn(Optional.of(invitation(3L, "kasia@gmail.com", Instant.now())));
        when(invitations.existsByIdAndEmailKeyAndInvitedAtAfter(eq(3L), eq("kasia@gmail.com"), any())).thenReturn(true);
        when(staff.addToStaff(pub, "kasia", "Kasia", EnumSet.of(StaffPermission.HISTORY), true)).thenReturn(PartyStaffService.JoinOutcome.FULL);

        assertThat(service.accept(3L, "kasia@gmail.com", "kasia", "Kasia").outcome()).isEqualTo(AnswerOutcome.FULL);
        verify(invitations).deleteForAddress(3L, "kasia@gmail.com");
    }

    /** Another person's invitation, one too old, one answered meanwhile (another tab), no verified address: nothing joins. */
    @Test
    void dolacz_onAnInvitationNotTheirs_orGone_joinsNothing() {
        when(invitations.findById(3L)).thenReturn(Optional.of(invitation(3L, "ola@gmail.com", Instant.now())));
        when(invitations.findById(4L)).thenReturn(Optional.of(invitation(4L, "kasia@gmail.com", Instant.now().minus(31, ChronoUnit.DAYS))));
        when(invitations.findById(5L)).thenReturn(Optional.of(invitation(5L, "kasia@gmail.com", Instant.now())));

        assertThat(service.accept(3L, "kasia@gmail.com", "kasia", "Kasia").outcome()).isEqualTo(AnswerOutcome.GONE);
        assertThat(service.accept(4L, "kasia@gmail.com", "kasia", "Kasia").outcome()).isEqualTo(AnswerOutcome.GONE);
        assertThat(service.accept(5L, "kasia@gmail.com", "kasia", "Kasia").outcome()).as("answered meanwhile").isEqualTo(AnswerOutcome.GONE);
        assertThat(service.accept(5L, null, "kasia", "Kasia").outcome()).isEqualTo(AnswerOutcome.GONE);
        verify(staff, never()).addToStaff(any(), anyString(), any(), any(), anyBoolean());
        verify(invitations, never()).deleteForAddress(anyLong(), anyString());
    }

    @Test
    void nieDziekuje_deletesOnlyAnInvitationForTheirAddress() {
        when(invitations.deleteForAddress(3L, "kasiakowalska@gmail.com")).thenReturn(1);

        assertThat(service.decline(3L, "Kasia.Kowalska@gmail.com")).isTrue();
        assertThat(service.decline(4L, "kasiakowalska@gmail.com")).isFalse();
        assertThat(service.decline(3L, null)).isFalse();
    }

    @Test
    void theOldInvitations_goAfterAStartToo_andAFailureNeverStopsIt() throws Exception {
        EventListener listener = StaffInvitationService.class.getMethod("atStart").getAnnotation(EventListener.class);
        assertThat(listener).isNotNull();
        assertThat(listener.value()).containsExactly(ApplicationReadyEvent.class);
        Instant before = Instant.now();

        service.atStart();

        ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
        verify(invitations).deleteMadeBefore(cutoff.capture());
        assertThat(cutoff.getValue()).isBefore(before.minus(StaffInvitationEntity.MAX_AGE_DAYS, ChronoUnit.DAYS).plusSeconds(60));

        when(invitations.deleteMadeBefore(any())).thenThrow(new IllegalStateException("database down"));
        service.atStart();
    }
}
