package com.scan2play.controller;

import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.repository.PartySettingsRepository;
import com.scan2play.service.PartySettingsCommandService;
import com.scan2play.service.PartySettingsQueryService;
import com.scan2play.service.PartyStaffService;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The panel's party (V30): the owner's own, or one the person works at — checked on every request, so an access the owner took away
 * ends at once; what only the owner may do refused to the staff.
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
    private final PartySettingsEntity pub = PartySettingsEntity.builder().partyCode(PUB).ownerId("pub-owner").build();

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
    }

    private static OAuth2AuthenticationToken user(String id) {
        return new OAuth2AuthenticationToken(new DefaultOAuth2User(AuthorityUtils.createAuthorityList("ROLE_USER"), Map.of("sub", id), "sub"),
                AuthorityUtils.createAuthorityList("ROLE_USER"), "google");
    }

    @Test
    void aStaffMember_staysOnThePub_whileTheyHaveAccess_andIsBackOnTheirOwnOnceItIsTakenAway() {
        when(parties.findByOwnerId("kasia")).thenReturn(Optional.of(own));
        when(staff.hasAccess(pub, "kasia")).thenReturn(true);
        helper.switchTo(PUB, user("kasia"), session);

        assertThat(helper.getPartySettings(user("kasia"), session).getPartyCode()).isEqualTo(PUB);
        assertThat(helper.isOwner(user("kasia"), session)).isFalse();

        when(staff.hasAccess(pub, "kasia")).thenReturn(false);   // the owner took the access away
        assertThat(helper.getPartySettings(user("kasia"), session).getPartyCode()).isEqualTo(OWN);
        assertThat(session.getAttribute(DjSessionHelper.SESSION_PARTY_CODE)).isEqualTo(OWN);
    }

    @Test
    void aBartenderWithoutAPartyOfTheirOwn_opensThePub_andGetsNoPartyMadeForThem() {
        when(parties.findByOwnerId("ola")).thenReturn(Optional.empty());
        when(staff.partiesOf("ola")).thenReturn(List.of(pub));

        assertThat(helper.getPartySettings(user("ola"), session).getPartyCode()).isEqualTo(PUB);
        verify(command, never()).getOrCreatePartyForDj(anyString());
    }

    @Test
    void aNewDj_getsTheirPartyMade() {
        when(parties.findByOwnerId("new")).thenReturn(Optional.empty());
        PartySettingsEntity made = PartySettingsEntity.builder().partyCode("NEW01").ownerId("new").build();
        when(command.getOrCreatePartyForDj("new")).thenReturn(made);

        assertThat(helper.getPartySettings(user("new"), session).getPartyCode()).isEqualTo("NEW01");
    }

    @Test
    void whatOnlyTheOwnerMayDo_isRefusedToTheStaff_theQueueIsNot() {
        when(staff.hasAccess(any(), anyString())).thenReturn(true);
        helper.switchTo(PUB, user("kasia"), session);

        assertThatThrownBy(() -> helper.getOwnedPartySettings(user("kasia"), session)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> helper.validateOwnership(PUB, user("kasia"), session)).isInstanceOf(AccessDeniedException.class);
        helper.validateAccess(PUB, user("kasia"), session);   // the queue, the history: no exception
        assertThatThrownBy(() -> helper.validateAccess(OWN, user("kasia"), session))
                .as("another party than the panel's").isInstanceOf(AccessDeniedException.class);

        when(staff.hasAccess(pub, "pub-owner")).thenReturn(true);
        MockHttpSession ownersSession = new MockHttpSession();
        helper.switchTo(PUB, user("pub-owner"), ownersSession);
        assertThat(helper.getOwnedPartySettings(user("pub-owner"), ownersSession).getPartyCode()).isEqualTo(PUB);
        helper.validateOwnership(PUB, user("pub-owner"), ownersSession);
    }

    @Test
    void aPartyThePersonDoesNotWorkAt_cannotBeOpened() {
        when(staff.hasAccess(pub, "stranger")).thenReturn(false);

        assertThatThrownBy(() -> helper.switchTo(PUB, user("stranger"), session)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> helper.switchTo("NOPE1", user("stranger"), session)).isInstanceOf(AccessDeniedException.class);
        assertThat(session.getAttribute(DjSessionHelper.SESSION_PARTY_CODE)).isNull();
    }

    @Test
    void myPanel_opensTheirOwnParty_madeNowForABartender() {
        when(command.getOrCreatePartyForDj("ola")).thenReturn(PartySettingsEntity.builder().partyCode("OLA01").ownerId("ola").build());

        assertThat(helper.switchToOwnParty(user("ola"), session).getPartyCode()).isEqualTo("OLA01");
        assertThat(session.getAttribute(DjSessionHelper.SESSION_PARTY_CODE)).isEqualTo("OLA01");
    }
}
