package com.scan2play.controller;

import com.scan2play.config.SecurityConfig;
import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.entity.SongRequestEntity;
import com.scan2play.service.GuestQueueService;
import com.scan2play.service.PartySettingsQueryService;
import com.scan2play.service.QrCodeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The screen for a TV (Next 7 c, an idea from Rekwest; the owner, 2026-10-18: public, the ten most wanted, plan Free): a page of its
 * own, {@code /p/{code}/tv}, with the party's QR code and the guests' most wanted songs — what the guests see under their form anyway,
 * the song and its votes, never the guests' words or dedications —, its list fetched again by {@code js/tv.js}
 * ({@code /p/{code}/tv/list}). No session: a TV keeps it open all night, and it has no form. Writes the pages of the browser tests
 * ({@code target/browser-harness/tv*.html}).
 */
@WebMvcTest(TvScreenController.class)
@Import({SecurityConfig.class, com.scan2play.service.PlansOffConfig.class,
        com.scan2play.service.PartySchedulesConfig.class})
class TvScreenWebTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PartySettingsQueryService partySettingsQueryService;
    @MockitoBean
    private GuestQueueService guestQueueService;
    @MockitoBean
    private QrCodeService qrCodeService;

    private static final List<String> SONGS = List.of("Zenek Martyniuk - Przez twe oczy zielone", "ABBA - Dancing Queen",
            "Dawid Podsiadło - Małomiasteczkowy", "Queen - Don't Stop Me Now", "Smash Mouth - All Star", "sanah - Szampan",
            "Daft Punk - One More Time", "Golec uOrkiestra - Hej sokoły", "Bajm - Dwa serca, dwa smutki", "Wilki - Baśka",
            "Lady Pank - Mniej niż zero");

    @BeforeEach
    void setUp() {
        doThrow(new IllegalArgumentException("no such party")).when(partySettingsQueryService).getSettings(anyString());
        doReturn(PartySettingsEntity.builder().partyCode("ABC12").active(true).djName("DJ Kuba").build())
                .when(partySettingsQueryService).getSettings("ABC12");
        doReturn(PartySettingsEntity.builder().partyCode("ENDED").active(false).build())
                .when(partySettingsQueryService).getSettings("ENDED");
        when(guestQueueService.mostWanted("ABC12")).thenReturn(songs(0));
        // the real code of the party's address (the browser tests show it), asked only for that address
        when(qrCodeService.generateQrCodeBase64(eq("https://www.scan2play.com.pl/p/ABC12"), anyInt(), anyInt())).thenAnswer(call ->
                new QrCodeService().generateQrCodeBase64(call.getArgument(0), call.getArgument(1), call.getArgument(2)));
    }

    /** The ten most wanted, as GuestQueueService gives them: the first {@code shift} songs left out. */
    private static List<SongRequestEntity> songs(int shift) {
        List<SongRequestEntity> songs = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            songs.add(SongRequestEntity.builder().id(100L + i + shift).songName(SONGS.get(i + shift)).requestNumber(12 + i + shift)
                    .votes(Math.max(0, 9 - i - shift)).guestText("guest words " + i).dedications(1).build());
        }
        return songs;
    }

    private static String noSession(MvcResult result) throws IOException {
        assertThat(result.getRequest().getSession(false)).as("a session was made").isNull();
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    /** The DJ's logo (V45, Pro) above our name, from our address; ours then smaller. None without one. */
    @Test
    void theDjsLogo_standsAboveOurs() throws Exception {
        doReturn(PartySettingsEntity.builder().partyCode("LOGO1").active(true).logoVersion("0123456789abcdef".repeat(4)).build())
                .when(partySettingsQueryService).getSettings("LOGO1");
        when(guestQueueService.mostWanted("LOGO1")).thenReturn(List.of());
        String html = mockMvc.perform(get("/p/LOGO1/tv")).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(html).contains("class=\"tv-dj-logo\"", "src=\"/p/LOGO1/logo.png?v=0123456789abcdef\"", "tv-logo tv-logo-small");
        assertThat(html.indexOf("tv-dj-logo")).isLessThan(html.indexOf("class=\"tv-logo"));
        assertThat(mockMvc.perform(get("/p/ABC12/tv")).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8))
                .doesNotContain("tv-dj-logo", "tv-logo-small");
    }

    /** A page of the browser tests: the stand-in serves the files at /js/, /css/ — not under the deploy's version ("/dev/js/"). */
    private static void write(String name, String html) throws IOException {
        Path out = Path.of("target", "browser-harness", name);
        Files.createDirectories(out.getParent());
        Files.writeString(out, html.replaceAll("\"/[^/\"]+/(js|css)/", "\"/$1/"), StandardCharsets.UTF_8);
    }

    @Test
    void theScreen_showsTheCode_andTheTenMostWanted_withoutASession() throws Exception {
        String html = noSession(mockMvc.perform(get("/p/ABC12/tv")).andExpect(status().isOk()).andReturn());

        assertThat(html).contains("data:image/png;base64,iVBOR", ">www.scan2play.com.pl/p/ABC12<", "DJ Kuba",
                "Zeskanuj i poproś o piosenkę", "Scan to request a song", "Najczęściej wybierane", "Most wanted",
                "id=\"tvList\"", "data-url=\"/p/ABC12/tv/list?lang=pl\"", "/js/tv.js\"", "/css/tv.css\"");
        assertThat(html.split("class=\"tv-song\"", -1)).as("ten songs").hasSize(11);
        assertThat(html).contains("Zenek Martyniuk - Przez twe oczy zielone", ">9<", "Wilki - Baśka")
                .doesNotContain("Lady Pank");
        assertThat(html).as("what the guests see under their form, no more: no words, no dedications, no numbers")
                .doesNotContain("guest words", "💌", "#12");
        assertThat(html).as("no form: nothing to post, no CSRF token").doesNotContain("<form", "_csrf");
        assertThat(html).as("nothing inline (the CSP)").doesNotContainPattern("<script(?![^>]*\\bsrc=)")
                .doesNotContainPattern("\\son[a-z]+=").doesNotContain("style=\"");
        write("tv.html", html);
    }

    /**
     * The owner, 2026-10-23: the screen speaks the DJ's panel's language — the panel's link says it (?lang=en), and the list's address
     * carries it on. English alone, as the primary text; a Polish screen (the default, a Polish TV) keeps both languages.
     */
    @Test
    void anEnglishScreen_isEnglishAlone_andItsListToo() throws Exception {
        String html = noSession(mockMvc.perform(get("/p/ABC12/tv").param("lang", "en")).andExpect(status().isOk()).andReturn());
        assertThat(html).contains("<html lang=\"en\"", "<span lang=\"en\">Scan to request a song</span>",
                "<span lang=\"en\">Most wanted</span>", "data-url=\"/p/ABC12/tv/list?lang=en\"", "alt=\"QR code\"");
        assertThat(html).doesNotContain("lang=\"pl\"", "class=\"second\"", "Zeskanuj", "Najczęściej");

        String list = noSession(mockMvc.perform(get("/p/ABC12/tv/list").param("lang", "en")).andExpect(status().isOk()).andReturn());
        assertThat(list).contains("<span lang=\"en\">Most wanted</span>").doesNotContain("Najczęściej");
        when(guestQueueService.mostWanted("ABC12")).thenReturn(List.of());
        assertThat(noSession(mockMvc.perform(get("/p/ABC12/tv/list").header("Accept-Language", "de-DE")).andReturn()))
                .as("a German TV: English").contains("<span lang=\"en\">No requests yet").doesNotContain("Jeszcze nikt");
        assertThat(noSession(mockMvc.perform(get("/p/ABC12/tv").param("lang", "pl")).andReturn()))
                .contains("<html lang=\"pl\"", "<span lang=\"pl\">Zeskanuj i poproś o piosenkę</span>",
                        "<span lang=\"en\" class=\"second\">Scan to request a song</span>");
    }

    @Test
    void theList_aloneIsFetchedAgain_withoutASession() throws Exception {
        String list = noSession(mockMvc.perform(get("/p/ABC12/tv/list")).andExpect(status().isOk()).andReturn());
        assertThat(list).startsWith("<section id=\"tvList\"").contains("Zenek Martyniuk").doesNotContain("<html", "data:image");
        assertThat(list.split("class=\"tv-song\"", -1)).hasSize(11);
        write("tv-list.html", list);

        // the next fetch, the room having voted: the browser tests see the screen change
        when(guestQueueService.mostWanted("ABC12")).thenReturn(songs(1));
        write("tv-list-next.html", noSession(mockMvc.perform(get("/p/ABC12/tv/list")).andExpect(status().isOk()).andReturn()));

        when(guestQueueService.mostWanted("ABC12")).thenReturn(List.of());
        String empty = noSession(mockMvc.perform(get("/p/ABC12/tv/list")).andExpect(status().isOk()).andReturn());
        assertThat(empty).contains("id=\"tvEmpty\"").doesNotContain("class=\"tv-song\"");
        write("tv-list-empty.html", empty);
    }

    @Test
    void aPartyThatHasEnded_saysSo_onTheScreenAndInItsList() throws Exception {
        String page = noSession(mockMvc.perform(get("/p/ENDED/tv")).andExpect(status().isOk()).andReturn());
        assertThat(page).contains("id=\"tvEnded\"", "DJ nie przyjmuje teraz próśb").doesNotContain("class=\"tv-song\"");
        String list = noSession(mockMvc.perform(get("/p/ENDED/tv/list")).andExpect(status().isOk()).andReturn());
        assertThat(list).contains("id=\"tvEnded\"");
        write("tv-list-ended.html", list);
    }

    /**
     * A closed party that opens by itself today at 20:00 (V55, seen on Friday 2026-10-30 at noon — PartySchedulesConfig): the screen
     * says "Prośby od 20:00" / "Requests from 20:00" in place of "nie przyjmuje", in both of its languages, or in English alone.
     */
    @Test
    void aPartyThatOpensLater_saysWhen_onTheScreen() throws Exception {
        doReturn(PartySettingsEntity.builder().partyCode("LATER").active(false)
                .nextOpenAt(java.time.Instant.parse("2026-10-30T19:00:00Z")).build())
                .when(partySettingsQueryService).getSettings("LATER");
        String list = noSession(mockMvc.perform(get("/p/LATER/tv/list")).andExpect(status().isOk()).andReturn());
        assertThat(list).contains("id=\"tvEnded\"", "<span lang=\"pl\">Prośby od 20:00</span>",
                "<span lang=\"en\" class=\"second\">Requests from 20:00</span>").doesNotContain("nie przyjmuje");
        write("tv-list-opens.html", list);
        assertThat(noSession(mockMvc.perform(get("/p/LATER/tv/list").param("lang", "en")).andExpect(status().isOk()).andReturn()))
                .contains("<span lang=\"en\">Requests from 20:00</span>").doesNotContain("lang=\"pl\"");
    }

    @Test
    void aCodeThatIsNoParty_isA404_withoutASession() throws Exception {
        noSession(mockMvc.perform(get("/p/X7Q2Z/tv")).andExpect(status().isNotFound()).andReturn());
        noSession(mockMvc.perform(get("/p/X7Q2Z/tv/list")).andExpect(status().isNotFound()).andReturn());
    }
}
