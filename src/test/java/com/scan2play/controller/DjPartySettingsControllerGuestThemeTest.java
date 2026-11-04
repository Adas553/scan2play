package com.scan2play.controller;

import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.model.GuestTheme;
import com.scan2play.model.StaffPermission;
import com.scan2play.service.AccountDeletionService;
import com.scan2play.service.PartySchedules;
import com.scan2play.service.PartySettingsCommandService;
import com.scan2play.service.PartyStaffService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * "Motyw dla gości" (V59): whoever may change the party's vibe (VIBE) picks the guests' look — any plan; sent in the background the
 * answer is the card alone, saved; without the script the page again at the card. An unknown theme is the classic one.
 */
class DjPartySettingsControllerGuestThemeTest {

    private static final String PARTY = "ABC12";

    private DjSessionHelper sessionHelper;
    private MockMvc mockMvc;
    private OAuth2AuthenticationToken token;
    private PartySettingsEntity row;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        PartySettingsCommandService settingsService = mock(PartySettingsCommandService.class);
        row = PartySettingsEntity.builder().partyCode(PARTY).active(true).build();
        when(settingsService.updateSettings(eq(PARTY), any())).thenAnswer(call -> {
            ((Consumer<PartySettingsEntity>) call.getArgument(1)).accept(row);
            return row;
        });
        sessionHelper = mock(DjSessionHelper.class);
        when(sessionHelper.require(eq(PARTY), eq(StaffPermission.VIBE), any(), any())).thenReturn(row);
        when(sessionHelper.access(eq(PARTY), any(), any())).thenAnswer(call -> new PartyStaffService.Access(row, false, StaffPermission.all()));
        mockMvc = MockMvcBuilders.standaloneSetup(new DjPartySettingsController(settingsService, mock(AccountDeletionService.class),
                mock(com.scan2play.service.PartyCodeService.class), sessionHelper, com.scan2play.service.Plans.enforced(),   // the plans enforced, no Pro: any plan may pick a theme
                PartySchedules.of(settingsService, PartySchedules.FRIDAY_NOON))).build();
        token = new OAuth2AuthenticationToken(
                new DefaultOAuth2User(AuthorityUtils.createAuthorityList("ROLE_USER"), Map.of("sub", "kasia"), "sub"),
                AuthorityUtils.createAuthorityList("ROLE_USER"), "google");
    }

    @Test
    void sentInTheBackground_isSaved_andAnsweredWithTheCard() throws Exception {
        mockMvc.perform(post("/dj/dashboard/guest-theme").param("partyCode", PARTY).param("theme", "SCARY")
                        .header("X-Requested-With", "fetch").principal(token).session(new MockHttpSession()))
                .andExpect(status().isOk()).andExpect(view().name("settings :: guestThemeCard"))
                .andExpect(model().attribute("guestTheme", GuestTheme.SCARY)).andExpect(model().attribute("guestThemeSaved", true));
        verify(sessionHelper).require(eq(PARTY), eq(StaffPermission.VIBE), any(), any());
        assertThat(row.getGuestTheme()).isEqualTo(GuestTheme.SCARY);
    }

    @Test
    void withoutTheScript_backToTheCard_andAnUnknownThemeIsTheClassicOne() throws Exception {
        row.setGuestTheme(GuestTheme.WEDDING);
        mockMvc.perform(post("/dj/dashboard/guest-theme").param("partyCode", PARTY).param("theme", "SUMMER")
                        .principal(token).session(new MockHttpSession()))
                .andExpect(redirectedUrl("/dj/settings?party=ABC12#guestThemeCard"));
        assertThat(row.getGuestTheme()).isEqualTo(GuestTheme.CLASSIC);
    }

    /** Without VIBE (the helper refuses): nothing saved. */
    @Test
    void withoutVibe_nothingIsSaved() throws Exception {
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN)).when(sessionHelper)
                .require(eq(PARTY), eq(StaffPermission.VIBE), any(), any());
        mockMvc.perform(post("/dj/dashboard/guest-theme").param("partyCode", PARTY).param("theme", "SCARY")
                .principal(token).session(new MockHttpSession())).andExpect(status().isForbidden());
        assertThat(row.getGuestTheme()).isEqualTo(GuestTheme.CLASSIC);
    }
}
