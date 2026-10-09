package com.scan2play.controller;

import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.model.CommentStyle;
import com.scan2play.model.VibeType;
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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The party's vibe: the DJ's own words about it ({@code POST /dj/dashboard/vibe-note}, V16) — one line, at most 150 characters,
 * empty clears them, only the party's own DJ — and the vibes of the list (V16: LATINO instead of three).
 */
class DjPartySettingsControllerVibeTest {

    private static final String PARTY = "ABC12";

    private PartySettingsCommandService settingsService;
    private DjSessionHelper sessionHelper;
    private MockMvc mockMvc;
    private OAuth2AuthenticationToken token;
    private MockHttpSession session;

    @BeforeEach
    void setUp() {
        settingsService = mock(PartySettingsCommandService.class);
        sessionHelper = mock(DjSessionHelper.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new DjPartySettingsController(
                settingsService, mock(AccountDeletionService.class), sessionHelper, mock(com.scan2play.service.PartyStaffService.class))).build();
        token = new OAuth2AuthenticationToken(
                new DefaultOAuth2User(AuthorityUtils.createAuthorityList("ROLE_USER"), Map.of("sub", "owner"), "sub"),
                AuthorityUtils.createAuthorityList("ROLE_USER"), "google");
        session = new MockHttpSession();
    }

    /** Posts the note and gives the party's note after what the controller asked the settings service to change. */
    @SuppressWarnings("unchecked")
    private String send(String note) throws Exception {
        var request = post("/dj/dashboard/vibe-note").param("partyCode", PARTY).principal(token).session(session);
        if (note != null) {
            request.param("vibeNote", note);
        }
        mockMvc.perform(request).andExpect(status().is3xxRedirection());
        ArgumentCaptor<Consumer<PartySettingsEntity>> updater = ArgumentCaptor.forClass(Consumer.class);
        verify(settingsService).updateSettings(eq(PARTY), updater.capture());
        PartySettingsEntity party = PartySettingsEntity.builder().partyCode(PARTY).vibeNote("the old note").build();
        updater.getValue().accept(party);
        return party.getVibeNote();
    }

    /** Posts who plays (V17) and gives the party's DJ name after what the controller asked the settings service to change. */
    @SuppressWarnings("unchecked")
    private String sendDjName(String name) throws Exception {
        var request = post("/dj/dashboard/dj-name").param("partyCode", PARTY).principal(token).session(session);
        if (name != null) {
            request.param("djName", name);
        }
        mockMvc.perform(request).andExpect(status().is3xxRedirection());
        ArgumentCaptor<Consumer<PartySettingsEntity>> updater = ArgumentCaptor.forClass(Consumer.class);
        verify(sessionHelper).validateOwnership(eq(PARTY), any(), any());
        verify(settingsService).updateSettings(eq(PARTY), updater.capture());
        PartySettingsEntity party = PartySettingsEntity.builder().partyCode(PARTY).djName("DJ Old").build();
        updater.getValue().accept(party);
        return party.getDjName();
    }

    @Test
    void whoPlays_isKeptOnOneLine() throws Exception {
        assertThat(sendDjName("  DJ\n Koko ")).isEqualTo("DJ Koko");
    }

    @Test
    void whoPlays_isCutTo60Characters() throws Exception {
        assertThat(sendDjName("x".repeat(200))).hasSize(PartySettingsEntity.DJ_NAME_MAX);
    }

    @Test
    void whoPlays_emptyClearsIt() throws Exception {
        assertThat(sendDjName("  ")).isNull();
    }

    @Test
    void theNote_isKeptOnOneLine() throws Exception {
        assertThat(send("  wesele 40+,\n  polskie przeboje,   bez rapu ")).isEqualTo("wesele 40+, polskie przeboje, bez rapu");
    }

    @Test
    void aLongNote_isCutTo150Characters() throws Exception {
        assertThat(send("x".repeat(400))).hasSize(PartySettingsEntity.VIBE_NOTE_MAX);
    }

    @Test
    void anEmptyNote_clearsIt() throws Exception {
        assertThat(send("   ")).isNull();
    }

    @Test
    void noNoteAtAll_clearsIt() throws Exception {
        assertThat(send(null)).isNull();
    }

    @Test
    void anotherDjsParty_isNotChanged() throws Exception {
        doThrow(new org.springframework.security.access.AccessDeniedException("not yours"))
                .when(sessionHelper).validateOwnership(eq(PARTY), any(), any());

        try {
            mockMvc.perform(post("/dj/dashboard/vibe-note").param("partyCode", PARTY).param("vibeNote", "x").principal(token).session(session));
        } catch (Exception expected) {
            // the access denial surfaces from the standalone MockMvc
        }
        verify(settingsService, never()).updateSettings(any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void theCommentStyle_isSaved_forTheDjsOwnParty_andAnUnknownOneIsRefused() throws Exception {
        mockMvc.perform(post("/dj/dashboard/comment-style").param("partyCode", PARTY).param("commentStyle", "SARCASTIC")
                .principal(token).session(session)).andExpect(status().is3xxRedirection());

        verify(sessionHelper).validateOwnership(eq(PARTY), any(), any());
        ArgumentCaptor<Consumer<PartySettingsEntity>> updater = ArgumentCaptor.forClass(Consumer.class);
        verify(settingsService).updateSettings(eq(PARTY), updater.capture());
        PartySettingsEntity party = PartySettingsEntity.builder().partyCode(PARTY).build();
        assertThat(party.getCommentStyle()).as("a new party's style").isEqualTo(CommentStyle.CLASSIC);
        updater.getValue().accept(party);
        assertThat(party.getCommentStyle()).isEqualTo(CommentStyle.SARCASTIC);

        mockMvc.perform(post("/dj/dashboard/comment-style").param("partyCode", PARTY).param("commentStyle", "RUDE")
                .principal(token).session(session)).andExpect(status().isBadRequest());
    }

    @Test
    void theLatinoVibe_canBeChosen_theThreeItReplacedCannot() throws Exception {
        mockMvc.perform(post("/dj/dashboard/vibe").param("partyCode", PARTY).param("newVibe", "LATINO").principal(token).session(session))
                .andExpect(status().is3xxRedirection());
        mockMvc.perform(post("/dj/dashboard/vibe").param("partyCode", PARTY).param("newVibe", "SALSA_AND_TIMBA").principal(token).session(session))
                .andExpect(status().isBadRequest());
        assertThat(VibeType.valueOf("KIDS")).isNotNull();
    }
}
