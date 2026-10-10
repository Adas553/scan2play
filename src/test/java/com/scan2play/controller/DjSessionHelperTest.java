package com.scan2play.controller;

import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.model.StaffPermission;
import com.scan2play.model.StaffRole;
import com.scan2play.repository.PartySettingsRepository;
import com.scan2play.service.PartySettingsCommandService;
import com.scan2play.service.PartySettingsQueryService;
import com.scan2play.service.PartyStaffService;
import com.scan2play.service.PartyStaffService.Access;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Which party a request works on and what the person may do there (V30, V32): the party the page names, checked on every request — an
 * access taken away or a permission changed counts at once; nothing falls back to another party; what was not handed over is
 * refused.
 */
class DjSessionHelperTest {

    private static final String OWN = "OWN01";
    private static final String PUB = "PUB01";

    private PartySettingsQueryService query;
    private PartySettingsCommandService command;
    private PartySettingsRepository parties;
    private PartyStaffService staff;
    private DjSessionHelper helper;
    private MockHttpSession session;
    private final PartySettingsEntity own = PartySettingsEntity.builder().partyCode(OWN).ownerId("kasia").build();
    private final PartySettingsEntity pub = PartySettingsEntity.builder().partyCode(PUB).ownerId("pub-owner").djName("Klub Ola").build();

    @BeforeEach
    void setUp() {
        query = mock(PartySettingsQueryService.class);
        command = mock(PartySettingsCommandService.class);
        parties = mock(PartySettingsRepository.class);
        staff = mock(PartyStaffService.class);
        helper = new DjSessionHelper(query, command, parties, staff);
        session = new MockHttpSession();
        when(query.getSettings(OWN)).thenReturn(own);
        when(query.getSettings(PUB)).thenReturn(pub);
        when(query.getSettings("NOPE1")).thenThrow(new IllegalArgumentException("no party"));
        when(staff.partiesOf(anyString())).thenReturn(List.of());
        when(staff.accessOf(own, "kasia")).thenReturn(Optional.of(new Access(own, true, StaffPermission.all())));
        when(staff.accessOf(pub, "pub-owner")).thenReturn(Optional.of(new Access(pub, true, StaffPermission.all())));
        when(staff.accessOf(pub, "kasia")).thenReturn(Optional.empty());
        when(staff.accessOf(pub, "stranger")).thenReturn(Optional.empty());
        when(parties.findByOwnerId(anyString())).thenReturn(Optional.empty());
        when(parties.findByOwnerId("kasia")).thenReturn(Optional.of(own));
    }

    private void kasiaWorksAtThePubAs(Set<StaffPermission> permissions) {
        when(staff.accessOf(pub, "kasia")).thenReturn(Optional.of(new Access(pub, false, permissions)));
    }

    private static OAuth2AuthenticationToken user(String id) {
        return new OAuth2AuthenticationToken(new DefaultOAuth2User(AuthorityUtils.createAuthorityList("ROLE_USER"), Map.of("sub", id), "sub"),
                AuthorityUtils.createAuthorityList("ROLE_USER"), "google");
    }

    @Test
    void thePanelAskedFor_isOpened_andBecomesTheDefault_whileThePersonMayOpenIt() {
        kasiaWorksAtThePubAs(StaffRole.QUEUE.permissions());

        Access access = helper.panel(PUB, user("kasia"), session).orElseThrow();

        assertThat(access.party().getPartyCode()).isEqualTo(PUB);
        assertThat(access.owner()).isFalse();
        assertThat(session.getAttribute(DjSessionHelper.SESSION_PARTY_CODE)).isEqualTo(PUB);
        assertThat(helper.panel(null, user("kasia"), session).orElseThrow().party().getPartyCode()).isEqualTo(PUB);
    }

    /** An access taken away: the next panel is the person's own, and it says why — naming the party she did work at. */
    @Test
    void anAccessTakenAway_opensTheirOwnParty_withANote() {
        kasiaWorksAtThePubAs(StaffRole.QUEUE.permissions());
        helper.switchTo(PUB, user("kasia"), session);

        when(staff.accessOf(pub, "kasia")).thenReturn(Optional.empty());   // the organiser took the access away

        assertThat(helper.panel(PUB, user("kasia"), session).orElseThrow().party().getPartyCode()).isEqualTo(OWN);
        assertThat(helper.takeNote(session)).contains(new DjSessionHelper.Note("dashboard.staff.removed", "Klub Ola", true));
        assertThat(helper.takeNote(session)).as("shown once").isEmpty();
    }

    /** A code typed in the address names nothing: the note does not say whose party it is (the organiser's name is not public). */
    @Test
    void aPartyThePersonNeverWorkedAt_isNotNamed() {
        assertThat(helper.panel(PUB, user("stranger"), session)).as("no party of their own, a note waits: no DJ's party made").isEmpty();
        assertThat(helper.takeNote(session)).contains(new DjSessionHelper.Note("dashboard.staff.no_access", null, true));
        verify(command, never()).getOrCreatePartyForDj(anyString());
    }

    /**
     * A bartender whose access was taken away, with no party of her own: no panel at all ("no-panel") — before, the queue's poll made
     * a DJ's party for her and the next page was an empty DJ panel without a word (the review, 2026-10-10).
     */
    @Test
    void aBartenderWhoseAccessWasTakenAway_getsNoPartyMadeForThem() {
        Access bartender = new Access(pub, false, StaffRole.QUEUE.permissions());
        when(staff.accessOf(pub, "ola")).thenReturn(Optional.of(bartender));
        helper.switchTo(PUB, user("ola"), session);
        when(staff.accessOf(pub, "ola")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> helper.access(PUB, user("ola"), session)).as("the poll: a 403, nothing made")
                .isInstanceOf(AccessDeniedException.class);
        assertThat(helper.panel(null, user("ola"), session)).isEmpty();
        verify(command, never()).getOrCreatePartyForDj(anyString());
    }

    @Test
    void aBartenderWithoutAPartyOfTheirOwn_opensThePub_andGetsNoPartyMadeForThem() {
        when(staff.partiesOf("ola")).thenReturn(List.of(pub));
        when(staff.accessOf(pub, "ola")).thenReturn(Optional.of(new Access(pub, false, StaffRole.QUEUE.permissions())));

        assertThat(helper.getPartySettings(user("ola"), session).getPartyCode()).isEqualTo(PUB);
        verify(command, never()).getOrCreatePartyForDj(anyString());
    }

    @Test
    void aNewDj_getsTheirPartyMade() {
        PartySettingsEntity made = PartySettingsEntity.builder().partyCode("NEW01").ownerId("new").build();
        when(command.getOrCreatePartyForDj("new")).thenReturn(made);
        when(staff.accessOf(made, "new")).thenReturn(Optional.of(new Access(made, true, StaffPermission.all())));

        assertThat(helper.getPartySettings(user("new"), session).getPartyCode()).isEqualTo("NEW01");
    }

    /**
     * The page names the party (V32, the review of 2026-10-10): a tab still showing the pub works on the pub, whatever another tab
     * made the default panel — before, "Wyczyść kolejkę" in such a tab cleared the person's own queue.
     */
    @Test
    void theRequestWorksOnTheParty_thePageNames_notOnTheSessionsDefault() {
        kasiaWorksAtThePubAs(StaffRole.QUEUE.permissions());
        helper.switchTo(OWN, user("kasia"), session);   // another tab opened her own panel

        assertThat(helper.require(PUB, StaffPermission.CLEAR_QUEUE, user("kasia"), session).getPartyCode()).isEqualTo(PUB);
        assertThat(helper.require(OWN, StaffPermission.CLEAR_QUEUE, user("kasia"), session).getPartyCode()).isEqualTo(OWN);
        assertThat(helper.require(null, StaffPermission.CLEAR_QUEUE, user("kasia"), session).getPartyCode())
                .as("a page of an older version, no code: the default panel").isEqualTo(OWN);
    }

    @Test
    void whatWasNotHandedOver_isRefused_whatWasIsNot() {
        kasiaWorksAtThePubAs(StaffRole.VIEWER.permissions());

        assertThatThrownBy(() -> helper.require(PUB, StaffPermission.QUEUE, user("kasia"), session)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> helper.require(PUB, StaffPermission.CLEAR_QUEUE, user("kasia"), session)).isInstanceOf(AccessDeniedException.class);
        assertThat(helper.require(PUB, StaffPermission.HISTORY, user("kasia"), session)).isSameAs(pub);
        helper.validateAccess(PUB, user("kasia"), session);   // the queue to look at: no exception

        kasiaWorksAtThePubAs(StaffRole.CO_ORGANISER.permissions());
        assertThat(helper.require(PUB, StaffPermission.VIBE, user("kasia"), session)).isSameAs(pub);
        assertThatThrownBy(() -> helper.requireOwner(PUB, user("kasia"), session)).as("the staff, the profiles, the tip link: never handed over")
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> helper.validateOwnership(PUB, user("kasia"), session)).isInstanceOf(AccessDeniedException.class);

        assertThat(helper.requireOwner(PUB, user("pub-owner"), new MockHttpSession())).isSameAs(pub);
        assertThat(helper.require(PUB, StaffPermission.SUMMARY, user("pub-owner"), new MockHttpSession())).isSameAs(pub);
    }

    @Test
    void aPartyThePersonDoesNotWorkAt_cannotBeOpened() {
        assertThatThrownBy(() -> helper.switchTo(PUB, user("stranger"), session)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> helper.switchTo("NOPE1", user("stranger"), session)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> helper.require(PUB, StaffPermission.HISTORY, user("stranger"), session)).isInstanceOf(AccessDeniedException.class);
        assertThat(session.getAttribute(DjSessionHelper.SESSION_PARTY_CODE)).isNull();
    }

    /** Leaving a staff: the next panel is another one, with the note of leaving — not "the organiser removed your access". */
    @Test
    void afterLeaving_theNextPanelSaysNothingOfAnAccessTakenAway() {
        kasiaWorksAtThePubAs(StaffRole.QUEUE.permissions());
        helper.switchTo(PUB, user("kasia"), session);
        when(staff.accessOf(pub, "kasia")).thenReturn(Optional.empty());   // she left
        helper.forget(PUB, session);
        helper.note(session, new DjSessionHelper.Note("dashboard.staff.left", "Klub Ola", false));

        assertThat(helper.panel(null, user("kasia"), session).orElseThrow().party().getPartyCode()).isEqualTo(OWN);
        assertThat(helper.takeNote(session)).contains(new DjSessionHelper.Note("dashboard.staff.left", "Klub Ola", false));
    }

    @Test
    void makingTheirOwnParty_opensIt_madeNowForABartender() {
        when(command.getOrCreatePartyForDj("ola")).thenReturn(PartySettingsEntity.builder().partyCode("OLA01").ownerId("ola").build());

        assertThat(helper.switchToOwnParty(user("ola"), session).getPartyCode()).isEqualTo("OLA01");
        assertThat(session.getAttribute(DjSessionHelper.SESSION_PARTY_CODE)).isEqualTo("OLA01");
    }
}
