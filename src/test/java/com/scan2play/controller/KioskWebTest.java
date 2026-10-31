package com.scan2play.controller;

import com.scan2play.config.SecurityConfig;
import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.entity.SongRequestEntity;
import com.scan2play.model.DjResponse;
import com.scan2play.repository.PartySettingsRepository;
import com.scan2play.service.GuestQueueService;
import com.scan2play.service.GuestRequestLimiter;
import com.scan2play.service.GuestSessionService;
import com.scan2play.service.GuestVoteService;
import com.scan2play.service.PartySettingsQueryService;
import com.scan2play.service.SongEvaluationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The venue's kiosk (V56) through the real security and controllers, with the real limits and the guests' sessions: its page and its
 * forms without a login (a CSRF token, as the guest page), nothing that leads out of it, a request marked as the kiosk's, the
 * kiosk's own limit in place of an address's, "Następny gość" a new session, a code that is no kiosk's a 404. Writes the pages of
 * the browser tests ({@code target/browser-harness/kiosk*.html}).
 */
@WebMvcTest(KioskController.class)
@Import({SecurityConfig.class, com.scan2play.service.PlansOffConfig.class, com.scan2play.service.PartySchedulesConfig.class,
        GuestRequestLimiter.class, GuestSessionService.class, com.scan2play.service.GuestRequestService.class})
class KioskWebTest {

    private static final String TOKEN = "Kiosk_123-xyzKiosk_123-x";
    private static final String KIOSK = "/k/" + TOKEN;
    /** A party of each test's own: the kiosk's limits are the application's, kept from one test to the next. */
    private static final java.util.concurrent.atomic.AtomicInteger PARTIES = new java.util.concurrent.atomic.AtomicInteger();
    private String partyCode;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PartySettingsRepository partySettingsRepository;
    @MockitoBean
    private PartySettingsQueryService partySettingsQueryService;
    @MockitoBean
    private GuestQueueService guestQueueService;
    @MockitoBean
    private GuestVoteService guestVoteService;
    @MockitoBean
    private SongEvaluationService songEvaluationService;
    @MockitoBean
    private com.scan2play.service.SiteCounter siteCounter;

    private PartySettingsEntity party;

    @BeforeEach
    void setUp() {
        partyCode = "KSK" + String.format("%02d", PARTIES.incrementAndGet());
        party = PartySettingsEntity.builder().partyCode(partyCode).ownerId("owner").active(true).djName("DJ Koko").kioskToken(TOKEN)
                .requestLimit(10).instagramUrl("https://www.instagram.com/dj.koko/").tipUrl("https://buycoffee.to/djkoko").build();
        when(partySettingsRepository.findByKioskToken(anyString())).thenReturn(Optional.empty());
        when(partySettingsRepository.findByKioskToken(TOKEN)).thenAnswer(call -> Optional.of(party));
        when(guestQueueService.mostWanted(partyCode)).thenReturn(List.of(
                song(7, "Wilki - Baśka", 5), song(8, "sanah - Szampan", 3), song(9, "Dawid Podsiadło - Małomiasteczkowy", 1)));
        when(guestVoteService.myVotes(any(), eq(partyCode))).thenReturn(Set.of());
        when(guestVoteService.vote(any(), eq(partyCode), eq(8L))).thenReturn(GuestVoteService.Result.COUNTED);
        when(songEvaluationService.evaluateAndSaveSong(eq(partyCode), anyString(), anyString(), any(), any(), anyBoolean()))
                .thenAnswer(call -> new DjResponse("accepted", "Klasyk, sala zaśpiewa.", "Wilki - Baśka", "title", 7L));
    }

    private SongRequestEntity song(long id, String name, int votes) {
        return SongRequestEntity.builder().id(id).partyCode(partyCode).songName(name).votes(votes).requestedAt(Instant.now()).build();
    }

    private static String csrf(String html) {
        Matcher token = Pattern.compile("name=\"_csrf\" value=\"([^\"]+)\"").matcher(html);
        assertThat(token.find()).as("the form's CSRF field").isTrue();
        return token.group(1);
    }

    private static void write(String name, String html) throws IOException {
        Path out = Path.of("target", "browser-harness");
        Files.createDirectories(out);
        Files.writeString(out.resolve(name), html, StandardCharsets.UTF_8);
    }

    private String page(MockHttpSession session) throws Exception {
        return mockMvc.perform(get(KIOSK).session(session)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    /** A request at the kiosk, as the tablet sends it: asynchronous (the AI), with the page's token. */
    private MvcResult send(MockHttpSession session, String song) throws Exception {
        String token = csrf(page(session));
        MvcResult started = mockMvc.perform(post(KIOSK + "/request").session(session).param("songName", song).param("_csrf", token))
                .andReturn();
        return started.getRequest().isAsyncStarted() ? mockMvc.perform(asyncDispatch(started)).andReturn() : started;
    }

    @Test
    void theKiosk_opensWithoutALogin_withTheFormAndTheMostWanted_andNothingThatLeadsOut() throws Exception {
        MvcResult result = mockMvc.perform(get(KIOSK)).andExpect(status().isOk())
                .andExpect(header().string("X-Robots-Tag", "noindex, nofollow")).andReturn();
        String html = result.getResponse().getContentAsString();
        assertThat(html).contains("<title>Poproś o piosenkę</title>", "data-kiosk-state=\"form\"", "id=\"requestForm\"",
                "action=\"" + KIOSK + "/request\"", "name=\"_csrf\"", "data-autocomplete=\"songs\"", "id=\"dedicationBox\"",
                ">Najczęściej wybierane<", "data-url=\"" + KIOSK + "/list\"", "action=\"" + KIOSK + "/vote\"", "Wilki - Baśka",
                "aria-label=\"Zagłosuj na Wilki - Baśka, głosów: 5\"", "action=\"" + KIOSK + "/next\"", ">Następny gość<",
                "<script src=\"/js/kiosk.js\">", "🎧 <span>DJ Koko</span>");
        String ask = html.substring(html.indexOf("class=\"s2p-kiosk-ask\""), html.indexOf("id=\"kioskList\""));
        assertThat(ask).as("\"Następny gość\" under the form, in its column: always on the screen, never under a long list")
                .contains("id=\"requestForm\"", "id=\"kioskNext\"", "data-kiosk-next");
        assertThat(ask.indexOf("id=\"kioskNext\"")).isGreaterThan(ask.indexOf("id=\"submitBtn\""));
        assertThat(html).as("no line under the field that says its placeholder again").doesNotContain("Wpisz wykonawcę i tytuł.");
        assertThat(html).as("a list read as one by a screen reader (no list-style), and a place outside it that reads a 👍's note out")
                .contains("<ol role=\"list\" class=\"s2p-kiosk-songs\">", "id=\"kioskAnnounce\" class=\"visually-hidden\" role=\"status\"");
        assertThat(html.indexOf("id=\"kioskAnnounce\"")).as("outside the list, which is put in place anew").isGreaterThan(
                html.indexOf("</section>", html.indexOf("id=\"kioskList\"")));
        assertThat(html).as("no link leads out of the kiosk: no profiles, no tip link, no footer, no way to the DJ's pages")
                .doesNotContain("<a ", "instagram", "buycoffee", "/dj/");
        write("kiosk.html", html);
        write("kiosk-list.html", mockMvc.perform(get(KIOSK + "/list")).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString());
    }

    @Test
    void aCodeThatIsNoKiosk_isA404() throws Exception {
        mockMvc.perform(get("/k/nope")).andExpect(status().isNotFound());
        mockMvc.perform(get("/k/nope/list")).andExpect(status().isNotFound());
    }

    /** A request at the kiosk: the guests' way, marked as the kiosk's; the answer on the kiosk's page, then "Gotowe — następny gość". */
    @Test
    void aRequest_goesTheGuestsWay_markedAsTheKiosks_andIsAnsweredOnTheKiosk() throws Exception {
        MockHttpSession session = new MockHttpSession();
        String html = send(session, "baśka wilki").getResponse().getContentAsString();
        verify(songEvaluationService).evaluateAndSaveSong(eq(partyCode), eq("baśka wilki"), anyString(), any(), any(), eq(true));
        assertThat(html).contains("data-kiosk-state=\"result\"", "id=\"kioskVerdict\"", "Klasyk, sala zaśpiewa.", "id=\"kioskDone\"",
                ">Gotowe — następny gość<").doesNotContain("id=\"requestForm\"", "<a ");
        assertThat(html).as("this turn's own song: no 👍 on it").doesNotContain("aria-label=\"Zagłosuj na Wilki - Baśka");
        write("kiosk-result.html", html);
    }

    /**
     * The kiosk's own limit, not an address's: three requests a minute, then a short break said on the kiosk — for every turn alike
     * (the next guest does not start it again).
     */
    @Test
    void theKiosksOwnLimit_holdsAcrossTheTurns() throws Exception {
        for (int i = 0; i < 3; i++) {
            MockHttpSession turn = new MockHttpSession();
            assertThat(send(turn, "song " + i).getResponse().getContentAsString()).contains("data-kiosk-state=\"result\"");
        }
        MockHttpSession next = new MockHttpSession();
        MvcResult refused = send(next, "song 4");
        assertThat(refused.getResponse().getRedirectedUrl()).isEqualTo(KIOSK);
        assertThat((String) refused.getFlashMap().get("errorMessage")).startsWith("Kiosk ma chwilę przerwy: spróbuj za ");
        verify(songEvaluationService, times(3)).evaluateAndSaveSong(eq(partyCode), anyString(), anyString(), any(), any(), eq(true));
    }

    /** "Następny gość": the session goes — the next guest has a new one (the DJ's limit and the 👍 again). */
    @Test
    void theNextGuest_hasANewSession() throws Exception {
        MockHttpSession session = new MockHttpSession();
        String token = csrf(page(session));
        mockMvc.perform(post(KIOSK + "/next").session(session).param("_csrf", token)).andExpect(redirectedUrl(KIOSK));
        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    void aVote_needsNoLogin_andComesBackAsTheList() throws Exception {
        MockHttpSession session = new MockHttpSession();
        String token = csrf(page(session));
        when(guestVoteService.myVotes(any(), eq(partyCode))).thenReturn(Set.of(8L));   // as after the vote
        String list = mockMvc.perform(post(KIOSK + "/vote").session(session).param("id", "8").param("on", "true").param("_csrf", token)
                        .header("X-Requested-With", "fetch"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(list.trim()).startsWith("<section id=\"kioskList\"");
        assertThat(list).as("the 👍 given: pressed, to take back").contains("name=\"on\" value=\"false\"", "aria-pressed=\"true\"", "is-given");
        verify(guestVoteService).vote(any(), eq(partyCode), eq(8L));
        write("kiosk-list-voted.html", list);
    }

    /** A form whose session ran out (a tablet left for the night): back to the kiosk with what was typed, never an error page. */
    @Test
    void anExpiredForm_comesBackToTheKiosk() throws Exception {
        MockHttpSession session = new MockHttpSession();
        mockMvc.perform(post(KIOSK + "/request").session(session).param("songName", "Wilki - Baśka").param("_csrf", "stale"))
                .andExpect(redirectedUrl(KIOSK));
        assertThat(page(session)).as("the typed song back in the field").contains("value=\"Wilki - Baśka\"");
        mockMvc.perform(post(KIOSK + "/next").param("_csrf", "stale")).andExpect(redirectedUrl(KIOSK));
        verify(songEvaluationService, never()).evaluateAndSaveSong(anyString(), anyString(), anyString(), any(), any(), anyBoolean());
    }

    /** The party closed: when it opens (V55) — no form, no list; the page loads itself again (kiosk.js). */
    @Test
    void aClosedParty_saysWhenItOpens() throws Exception {
        party.setActive(false);
        party.setNextOpenAt(Instant.parse("2026-10-30T19:00:00Z"));
        String html = mockMvc.perform(get(KIOSK)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(html).contains("data-kiosk-state=\"closed\"", "id=\"kioskClosed\"", ">Prośby od 20:00<")
                .doesNotContain("id=\"requestForm\"", "id=\"kioskList\"");
        write("kiosk-closed.html", html);
    }

    /**
     * The kiosk opened in a browser where the DJ is logged in ("Otwórz kiosk" on the settings page, or the tablet the DJ set it up
     * on): "Następny gość" ends the guest's turn — the limit, the requests and the 👍 of the party —, never the DJ's login.
     */
    @Test
    void theNextGuest_inABrowserWithTheDjLoggedIn_keepsTheLogin() throws Exception {
        MockHttpSession session = new MockHttpSession();
        org.springframework.security.core.context.SecurityContext login =
                org.springframework.security.core.context.SecurityContextHolder.createEmptyContext();
        login.setAuthentication(new org.springframework.security.authentication.TestingAuthenticationToken("dj", null, "ROLE_USER"));
        session.setAttribute("SPRING_SECURITY_CONTEXT", login);
        send(session, "baśka wilki");
        assertThat(session.getAttribute("myRequests_" + partyCode)).as("the turn's request remembered").isNotNull();

        String token = csrf(page(session));
        mockMvc.perform(post(KIOSK + "/next").session(session).param("_csrf", token)).andExpect(redirectedUrl(KIOSK));

        assertThat(session.isInvalid()).as("the DJ's session, with the login").isFalse();
        assertThat(session.getAttribute("SPRING_SECURITY_CONTEXT")).isSameAs(login);
        assertThat(session.getAttribute("myRequests_" + partyCode)).as("the turn's request forgotten").isNull();
        verify(guestVoteService).forget(any(), eq(partyCode));
    }

    /**
     * A turn begun stays a turn when the page loads again: a refused request (here a mood, sent back with what was typed and the
     * dedication) or a 👍 given — kiosk.js then ends it after a minute without a touch, so the next guest never finds the last one's
     * words, dedication or votes. A fresh page is no one's turn. Every page names the kiosk's own address: the way back for kiosk.js
     * (never a reload, which on an answer would send the request again). Writes {@code kiosk-refused.html}.
     */
    @Test
    void aRefusedRequestOrAVote_leavesTheTurnBegun_aFreshPageNone() throws Exception {
        when(songEvaluationService.evaluateAndSaveSong(eq(partyCode), eq("coś skocznego"), anyString(), any(), any(), anyBoolean()))
                .thenReturn(new DjResponse("rejected", "", null, DjResponse.KIND_MOOD));
        MockHttpSession session = new MockHttpSession();
        String fresh = page(session);
        assertThat(fresh).contains("data-kiosk-url=\"" + KIOSK + "\"").doesNotContain("data-kiosk-turn");

        MvcResult started = mockMvc.perform(post(KIOSK + "/request").session(session).param("songName", "coś skocznego")
                .param("dedication", "dla Kasi").param("_csrf", csrf(fresh))).andReturn();
        MvcResult refused = mockMvc.perform(asyncDispatch(started)).andExpect(redirectedUrl(KIOSK)).andReturn();
        String html = mockMvc.perform(get(KIOSK).session(session).flashAttrs(refused.getFlashMap())).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(html).contains("data-kiosk-turn=\"begun\"", "data-kiosk-state=\"form\"", "value=\"coś skocznego\"",
                "value=\"dla Kasi\"", "id=\"kioskError\"");
        write("kiosk-refused.html", html);

        when(guestVoteService.myVotes(any(), eq(partyCode))).thenReturn(Set.of(8L));   // a 👍 given, then the page again
        assertThat(page(new MockHttpSession())).contains("data-kiosk-turn=\"begun\"");
    }

    /** The kiosk's limit of 👍 reached (one person piling votes turn after turn): said as the kiosk's, not "a network's". */
    @Test
    void theKiosksLimitOfVotes_isSaidAsTheKiosks() throws Exception {
        MockHttpSession session = new MockHttpSession();
        String token = csrf(page(session));
        String list = null;
        for (int i = 0; i <= GuestRequestLimiter.KIOSK_VOTES; i++) {
            list = mockMvc.perform(post(KIOSK + "/vote").session(session).param("id", "8").param("on", "true").param("_csrf", token)
                    .header("X-Requested-With", "fetch")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        }
        assertThat(list).contains("id=\"kioskVoteNote\"", "Kiosk dostał teraz dużo głosów").doesNotContain("z tej sieci");
        write("kiosk-list-note.html", list);
        verify(guestVoteService, times(GuestRequestLimiter.KIOSK_VOTES)).vote(any(), eq(partyCode), eq(8L));
    }

    /**
     * The list fetched again says whether the party is still open (data-active): a party closed while the form is on the screen
     * sends kiosk.js back to the kiosk's page, which says when it opens. Writes {@code kiosk-list-closed.html}.
     */
    @Test
    void theListFetchedAgain_saysWhetherThePartyIsOpen() throws Exception {
        assertThat(mockMvc.perform(get(KIOSK + "/list")).andReturn().getResponse().getContentAsString()).contains("data-active=\"true\"");
        party.setActive(false);
        String closed = mockMvc.perform(get(KIOSK + "/list")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(closed).contains("id=\"kioskList\"", "data-active=\"false\"");
        write("kiosk-list-closed.html", closed);
    }

    /**
     * The list as full as it gets (the ten most wanted, as the TV screen; long names among them): the pages of the browser tests that
     * check the kiosk fits one screen of a tablet — the form, "Następny gość" (or "Gotowe") and the list, which scrolls in its card.
     * Writes {@code kiosk-full.html} and {@code kiosk-result-full.html}.
     */
    @Test
    void theFullList_isRenderedForTheScreenChecks() throws Exception {
        String[] names = {"Golec uOrkiestra - Ściernisco", "Dua Lipa - Levitating", "Shakira - Whenever, Wherever",
                "Gente de Zona - La Gozadera (feat. Marc Anthony)", "Beyoncé - Crazy in Love (feat. JAY-Z)", "Dua Lipa - Physical",
                "Rihanna - Don't Stop The Music", "Shakira & Burna Boy - Dai Dai", "Eminem - Without Me",
                "Varius Manx - Orła cień (Remastered 2024 Version)"};
        List<SongRequestEntity> full = new java.util.ArrayList<>();
        for (int i = 0; i < names.length; i++) {
            full.add(song(20 + i, names[i], 12 - i));
        }
        when(guestQueueService.mostWanted(partyCode)).thenReturn(full);
        String page = page(new MockHttpSession());
        assertThat(page).contains("Varius Manx - Orła cień");
        write("kiosk-full.html", page);
        write("kiosk-result-full.html", send(new MockHttpSession(), "baśka wilki").getResponse().getContentAsString());
    }
}
