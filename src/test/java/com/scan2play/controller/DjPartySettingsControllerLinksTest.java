package com.scan2play.controller;

import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.service.AccountDeletionService;
import com.scan2play.service.PartySettingsCommandService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The DJ's profiles ({@code POST /dj/dashboard/dj-links}, V24): kept as https addresses on their sites, all or nothing. */
class DjPartySettingsControllerLinksTest {

    private static final String PARTY = "ABC12";

    private PartySettingsCommandService settingsService;
    private DjSessionHelper sessionHelper;
    private final com.scan2play.service.PartyStaffService staffService = mock(com.scan2play.service.PartyStaffService.class);
    private MockMvc mockMvc;
    private OAuth2AuthenticationToken token;

    @BeforeEach
    void setUp() {
        settingsService = mock(PartySettingsCommandService.class);
        sessionHelper = mock(DjSessionHelper.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new DjPartySettingsController(
                settingsService, mock(AccountDeletionService.class), sessionHelper, staffService)).build();
        token = new OAuth2AuthenticationToken(
                new DefaultOAuth2User(AuthorityUtils.createAuthorityList("ROLE_USER"), Map.of("sub", "owner"), "sub"),
                AuthorityUtils.createAuthorityList("ROLE_USER"), "google");
    }

    @Test
    @SuppressWarnings("unchecked")
    void theProfiles_areKeptAsAddressesOnTheirSites_andAnEmptyOneClearsIt() throws Exception {
        mockMvc.perform(post("/dj/dashboard/dj-links").param("partyCode", PARTY).principal(token).session(new MockHttpSession())
                        .param("instagram", " @dj.koko ").param("facebook", "https://m.facebook.com/djkoko?ref=share").param("tiktok", ""))
                .andExpect(status().is3xxRedirection());

        verify(sessionHelper).validateOwnership(eq(PARTY), any(), any());
        ArgumentCaptor<Consumer<PartySettingsEntity>> updater = ArgumentCaptor.forClass(Consumer.class);
        verify(settingsService).updateSettings(eq(PARTY), updater.capture());
        PartySettingsEntity party = PartySettingsEntity.builder().partyCode(PARTY).tiktokUrl("https://www.tiktok.com/@old").build();
        updater.getValue().accept(party);
        assertThat(party.getInstagramUrl()).isEqualTo("https://www.instagram.com/dj.koko/");
        assertThat(party.getFacebookUrl()).isEqualTo("https://www.facebook.com/djkoko");
        assertThat(party.getTiktokUrl()).isNull();
    }

    /** The tip link (V27, {@code POST /dj/dashboard/tip-link}): kept as an address on the service, an empty one clears it. */
    @Test
    @SuppressWarnings("unchecked")
    void theTipLink_isKeptAsAnAddressOnItsService_andAnEmptyOneClearsIt() throws Exception {
        mockMvc.perform(post("/dj/dashboard/tip-link").param("partyCode", PARTY).principal(token).session(new MockHttpSession())
                        .param("tip", " revolut.me/djkoko?currency=PLN "))
                .andExpect(status().is3xxRedirection());
        mockMvc.perform(post("/dj/dashboard/tip-link").param("partyCode", PARTY).principal(token).session(new MockHttpSession())
                        .param("tip", ""))
                .andExpect(status().is3xxRedirection());

        verify(sessionHelper, org.mockito.Mockito.times(2)).validateOwnership(eq(PARTY), any(), any());
        ArgumentCaptor<Consumer<PartySettingsEntity>> updater = ArgumentCaptor.forClass(Consumer.class);
        verify(settingsService, org.mockito.Mockito.times(2)).updateSettings(eq(PARTY), updater.capture());
        PartySettingsEntity party = PartySettingsEntity.builder().partyCode(PARTY).build();
        updater.getAllValues().get(0).accept(party);
        assertThat(party.getTipUrl()).isEqualTo("https://revolut.me/djkoko");
        updater.getAllValues().get(1).accept(party);
        assertThat(party.getTipUrl()).isNull();
    }

    @Test
    void aTipLinkOnAnotherSite_isRefused_andNothingIsSaved() throws Exception {
        mockMvc.perform(post("/dj/dashboard/tip-link").param("partyCode", PARTY).principal(token).session(new MockHttpSession())
                        .param("tip", "https://evil.example/revolut.me/djkoko"))
                .andExpect(status().isBadRequest());

        verify(settingsService, never()).updateSettings(any(), any());
    }

    @Test
    void anAddressOnAnotherSite_isRefused_andNothingIsSaved() throws Exception {
        mockMvc.perform(post("/dj/dashboard/dj-links").param("partyCode", PARTY).principal(token).session(new MockHttpSession())
                        .param("instagram", "@djkoko").param("facebook", "https://evil.example/facebook.com/djkoko"))
                .andExpect(status().isBadRequest());

        verify(settingsService, never()).updateSettings(any(), any());
    }

    /** The hosts' lists (V29, {@code POST /dj/dashboard/host-lists}): one entry per line, an empty one clears it. */
    @Test
    @SuppressWarnings("unchecked")
    void theHostsLists_areKeptOneEntryPerLine_andAnEmptyOneClearsIt() throws Exception {
        mockMvc.perform(post("/dj/dashboard/host-lists").param("partyCode", PARTY).principal(token).session(new MockHttpSession())
                        .param("blocked", " Akcent \r\n\r\nakcent\nBaby Shark").param("wanted", "  "))
                .andExpect(status().is3xxRedirection());

        verify(sessionHelper).validateOwnership(eq(PARTY), any(), any());
        ArgumentCaptor<Consumer<PartySettingsEntity>> updater = ArgumentCaptor.forClass(Consumer.class);
        verify(settingsService).updateSettings(eq(PARTY), updater.capture());
        PartySettingsEntity party = PartySettingsEntity.builder().partyCode(PARTY).hostWanted("Old").build();
        updater.getValue().accept(party);
        assertThat(party.getHostBlocked()).isEqualTo("Akcent\nBaby Shark");
        assertThat(party.getHostWanted()).isNull();
    }

    /** The hosts' link (V29): "new" a fresh secret each time (the old link dead), "off" none, anything else a 400. */
    @Test
    @SuppressWarnings("unchecked")
    void theHostsLink_isANewSecretEachTime_offTakesItAway() throws Exception {
        for (String action : new String[]{"new", "new", "off"}) {
            mockMvc.perform(post("/dj/dashboard/host-link").param("partyCode", PARTY).param("link", action).principal(token)
                    .session(new MockHttpSession())).andExpect(status().is3xxRedirection());
        }
        mockMvc.perform(post("/dj/dashboard/host-link").param("partyCode", PARTY).param("link", "x").principal(token)
                .session(new MockHttpSession())).andExpect(status().isBadRequest());

        ArgumentCaptor<Consumer<PartySettingsEntity>> updater = ArgumentCaptor.forClass(Consumer.class);
        verify(settingsService, org.mockito.Mockito.times(3)).updateSettings(eq(PARTY), updater.capture());
        PartySettingsEntity party = PartySettingsEntity.builder().partyCode(PARTY).build();
        updater.getAllValues().get(0).accept(party);
        String first = party.getHostToken();
        updater.getAllValues().get(1).accept(party);
        assertThat(first).hasSize(22).matches("[A-Za-z0-9_-]+");
        assertThat(party.getHostToken()).hasSize(22).isNotEqualTo(first);
        updater.getAllValues().get(2).accept(party);
        assertThat(party.getHostToken()).isNull();
    }

    /** The staff's invitation link (V30): the owner's, a new secret each time, "off" none, anything else a 400. */
    @Test
    @SuppressWarnings("unchecked")
    void theStaffsLink_isANewSecretEachTime_offTakesItAway_andOnlyTheOwnerMakesIt() throws Exception {
        for (String link : new String[]{"new", "off"}) {
            mockMvc.perform(post("/dj/dashboard/staff-link").param("partyCode", PARTY).param("link", link).principal(token)
                    .session(new MockHttpSession())).andExpect(status().is3xxRedirection());
        }
        mockMvc.perform(post("/dj/dashboard/staff-link").param("partyCode", PARTY).param("link", "x").principal(token)
                .session(new MockHttpSession())).andExpect(status().isBadRequest());

        verify(sessionHelper, org.mockito.Mockito.times(3)).validateOwnership(eq(PARTY), any(), any());
        ArgumentCaptor<Consumer<PartySettingsEntity>> updater = ArgumentCaptor.forClass(Consumer.class);
        verify(settingsService, org.mockito.Mockito.times(2)).updateSettings(eq(PARTY), updater.capture());
        PartySettingsEntity party = PartySettingsEntity.builder().partyCode(PARTY).build();
        updater.getAllValues().get(0).accept(party);
        assertThat(party.getStaffToken()).hasSize(22);
        updater.getAllValues().get(1).accept(party);
        assertThat(party.getStaffToken()).isNull();
    }

    /** Taking a person's access away (V30): checked as the owner's, then only a row of the owner's own party. */
    @Test
    void removingAccess_isTheOwners_andOfTheirOwnParty() throws Exception {
        org.mockito.Mockito.doThrow(new org.springframework.security.access.AccessDeniedException("staff"))
                .when(sessionHelper).validateOwnership(eq("OTHER"), any(), any());

        mockMvc.perform(post("/dj/dashboard/staff-remove").param("partyCode", PARTY).param("id", "7").principal(token)
                .session(new MockHttpSession())).andExpect(status().is3xxRedirection());
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> mockMvc.perform(post("/dj/dashboard/staff-remove")
                .param("partyCode", "OTHER").param("id", "8").principal(token).session(new MockHttpSession())))
                .hasRootCauseInstanceOf(org.springframework.security.access.AccessDeniedException.class);

        verify(staffService).remove(PARTY, 7L);
        verify(staffService, never()).remove(eq("OTHER"), org.mockito.ArgumentMatchers.anyLong());
    }
}
