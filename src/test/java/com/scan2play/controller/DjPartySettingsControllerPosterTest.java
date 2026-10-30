package com.scan2play.controller;

import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.model.PosterLanguage;
import com.scan2play.model.StaffPermission;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The QR print as the DJ sets it ({@code POST /dj/qr-print/settings}, V46): the DJ's heading and line one line each and no longer than
 * their columns (empty = ours / none), the language (anything else is "as the panel"), what is shown (a box not sent is off) — for whom
 * may change the card "Impreza", answered 204 for the print page's script.
 */
class DjPartySettingsControllerPosterTest {

    private static final String PARTY = "ABC12";

    private PartySettingsCommandService settingsService;
    private DjSessionHelper sessionHelper;
    private MockMvc mockMvc;
    private OAuth2AuthenticationToken token;

    @BeforeEach
    void setUp() {
        settingsService = mock(PartySettingsCommandService.class);
        sessionHelper = mock(DjSessionHelper.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new DjPartySettingsController(
                settingsService, mock(AccountDeletionService.class), mock(com.scan2play.service.PartyCodeService.class), sessionHelper, com.scan2play.service.Plans.off())).build();
        token = new OAuth2AuthenticationToken(
                new DefaultOAuth2User(AuthorityUtils.createAuthorityList("ROLE_USER"), Map.of("sub", "owner"), "sub"),
                AuthorityUtils.createAuthorityList("ROLE_USER"), "google");
    }

    /** Sends the editor's form and gives the party after what the controller asked the settings service to change. */
    private PartySettingsEntity send(MockHttpServletRequestBuilder request) throws Exception {
        return send(request, PartySettingsEntity.builder().partyCode(PARTY).posterHeading("old").posterLine("old").build());
    }

    @SuppressWarnings("unchecked")
    private PartySettingsEntity send(MockHttpServletRequestBuilder request, PartySettingsEntity party) throws Exception {
        mockMvc.perform(request.param("partyCode", PARTY).principal(token).session(new MockHttpSession()))
                .andExpect(status().isNoContent());
        verify(sessionHelper).require(eq(PARTY), eq(StaffPermission.VIBE), any(), any());
        ArgumentCaptor<Consumer<PartySettingsEntity>> updater = ArgumentCaptor.forClass(Consumer.class);
        verify(settingsService).updateSettings(eq(PARTY), updater.capture());
        updater.getValue().accept(party);
        return party;
    }

    @Test
    void theWordsTheLanguageAndTheParts_areSaved() throws Exception {
        PartySettingsEntity party = send(post("/dj/qr-print/settings").param("heading", "  Osiemnastka\n Dawida ")
                .param("line", "Sobota, 20:00").param("language", "pl").param("showLogo", "true").param("showTip", "true"));

        assertThat(party.getPosterHeading()).isEqualTo("Osiemnastka Dawida");
        assertThat(party.getPosterLine()).isEqualTo("Sobota, 20:00");
        assertThat(party.getPosterLanguage()).isEqualTo(PosterLanguage.PL);
        assertThat(party.isPosterShowLogo()).isTrue();
        assertThat(party.isPosterShowTip()).isTrue();
        assertThat(party.isPosterShowLinks()).as("a box not sent is off").isFalse();
        assertThat(party.isPosterShowAddress()).isFalse();
    }

    @Test
    void longerWords_areCut_emptyOnes_areOurs_andAnUnknownLanguage_isThePanels() throws Exception {
        PartySettingsEntity party = send(post("/dj/qr-print/settings").param("heading", "W".repeat(100)).param("line", "x".repeat(100))
                .param("language", "DE"));
        assertThat(party.getPosterHeading()).hasSize(PartySettingsEntity.POSTER_HEADING_MAX);
        assertThat(party.getPosterLine()).hasSize(PartySettingsEntity.POSTER_LINE_MAX);
        assertThat(party.getPosterLanguage()).isEqualTo(PosterLanguage.AUTO);

        setUp();
        PartySettingsEntity cleared = send(post("/dj/qr-print/settings").param("heading", "   ").param("line", ""));
        assertThat(cleared.getPosterHeading()).isNull();
        assertThat(cleared.getPosterLine()).isNull();
    }

    /** The look (V47): a theme by its name, the DJ's colour as "#rrggbb" in lower case; anything else the classic / the theme's own. */
    @Test
    void theThemeAndTheColour_areSaved_aWrongOne_isTheDefault() throws Exception {
        PartySettingsEntity party = send(post("/dj/qr-print/settings").param("theme", "club").param("accent", "#00BCD4"));
        assertThat(party.getPosterTheme()).isEqualTo(com.scan2play.model.PosterTheme.CLUB);
        assertThat(party.getPosterAccent()).isEqualTo("#00bcd4");

        setUp();
        assertThat(send(post("/dj/qr-print/settings").param("theme", "confetti")).getPosterTheme()).isEqualTo(com.scan2play.model.PosterTheme.CONFETTI);

        setUp();
        PartySettingsEntity wrong = send(post("/dj/qr-print/settings").param("theme", "WINTER").param("accent", "red; background:url(x)"));
        assertThat(wrong.getPosterTheme()).isEqualTo(com.scan2play.model.PosterTheme.CLASSIC);
        assertThat(wrong.getPosterAccent()).isNull();
    }

    /** The number of tables (V50): 1 … 50, more is 50; empty, zero or not a number — none (no numbers on the cards and the tents). */
    @Test
    void theTables_areANumberUpTo50_anythingElse_none() throws Exception {
        assertThat(send(post("/dj/qr-print/settings").param("tables", " 12 ")).getPosterTables()).isEqualTo(12);
        for (String value : new String[] {"120", "50"}) {
            setUp();
            assertThat(send(post("/dj/qr-print/settings").param("tables", value)).getPosterTables()).as(value).isEqualTo(PartySettingsEntity.POSTER_TABLES_MAX);
        }
        for (String value : new String[] {"", "0", "-3", "dwanaście", "1e3", "99999999999"}) {
            setUp();
            PartySettingsEntity party = PartySettingsEntity.builder().partyCode(PARTY).posterTables(7).build();
            assertThat(send(post("/dj/qr-print/settings").param("tables", value), party).getPosterTables()).as(value).isNull();
        }
        setUp();
        assertThat(send(post("/dj/qr-print/settings"), PartySettingsEntity.builder().partyCode(PARTY).posterTables(7).build())
                .getPosterTables()).as("not sent: none").isNull();
    }

    /** The paper's colour (V53): "#rrggbb" in lower case; anything else none — the theme's. */
    @Test
    void thePapersColour_isSaved_aWrongOne_isTheThemes() throws Exception {
        assertThat(send(post("/dj/qr-print/settings").param("paper", "#FBF3E2")).getPosterPaper()).isEqualTo("#fbf3e2");
        setUp();
        assertThat(send(post("/dj/qr-print/settings").param("paper", "url(x)")).getPosterPaper()).isNull();
        setUp();
        assertThat(send(post("/dj/qr-print/settings").param("paper", "")).getPosterPaper()).isNull();
    }

    /** The heading's size (V54): by its name; anything else as the theme has it. */
    @Test
    void theHeadingsSize_isSavedByName() throws Exception {
        assertThat(send(post("/dj/qr-print/settings").param("size", "large")).getPosterSize()).isEqualTo(com.scan2play.model.PosterSize.LARGE);
        setUp();
        assertThat(send(post("/dj/qr-print/settings").param("size", "HUGE")).getPosterSize()).isEqualTo(com.scan2play.model.PosterSize.NORMAL);
        setUp();
        assertThat(send(post("/dj/qr-print/settings")).getPosterSize()).isEqualTo(com.scan2play.model.PosterSize.NORMAL);
    }

    /** The heading's font (V51): by its name; anything else the theme's. */
    @Test
    void theHeadingsFont_isSaved_aWrongOne_isTheThemes() throws Exception {
        assertThat(send(post("/dj/qr-print/settings").param("font", "script")).getPosterFont()).isEqualTo(com.scan2play.model.PosterFont.SCRIPT);
        setUp();
        assertThat(send(post("/dj/qr-print/settings").param("font", "COMIC")).getPosterFont()).isEqualTo(com.scan2play.model.PosterFont.THEME);
        setUp();
        assertThat(send(post("/dj/qr-print/settings")).getPosterFont()).as("not sent").isEqualTo(com.scan2play.model.PosterFont.THEME);
    }

    @Test
    void theLanguage_printed_followsThePanelOnlyWhenAuto() {
        assertThat(PosterLanguage.AUTO.printed("pl")).isEqualTo("both");
        assertThat(PosterLanguage.AUTO.printed("de")).isEqualTo("en");
        assertThat(PosterLanguage.PL.printed("en")).isEqualTo("pl");
        assertThat(PosterLanguage.EN.printed("pl")).isEqualTo("en");
        assertThat(PosterLanguage.BOTH.printed("en")).isEqualTo("both");
    }
}
