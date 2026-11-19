package com.scan2play.controller;

import com.scan2play.config.SecurityConfig;
import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.service.GuestQueueService;
import com.scan2play.service.GuestRequestLimiter;
import com.scan2play.service.GuestSessionService;
import com.scan2play.service.GuestVoteService;
import com.scan2play.service.PartySettingsQueryService;
import com.scan2play.service.PollService;
import com.scan2play.service.QrCodeService;
import com.scan2play.service.SongEvaluationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.session.MapSession;
import org.springframework.session.MapSessionRepository;
import org.springframework.session.web.http.SessionRepositoryFilter;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import jakarta.servlet.http.Cookie;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * "Pytanie do gości" (V74) through the real web layer — security, CSRF, Spring Session's filter (sessions in memory, as the JDBC ones in
 * production) and the mock response's buffer: the question above the form on a first visit, with its form's token and the whole page;
 * on the page "the DJ is not taking requests" too — the DJ asked it alone —, which then makes the session its form needs; an answer
 * without a login, back as the card with the results; a vote without the token refused before anything is counted; the TV showing the
 * question in place of the list. Leaves {@code guest-poll*.html} and {@code tv-poll.html} in {@code target/browser-harness/} for the
 * browser tests.
 */
@WebMvcTest({GuestController.class, GuestPollController.class, TvScreenController.class})
@Import({PollView.class, SecurityConfig.class, com.scan2play.service.PlansOffConfig.class,
        com.scan2play.service.PartySchedulesConfig.class, com.scan2play.service.GuestRequestService.class, GuestPollWebTest.Sessions.class})
class GuestPollWebTest {

    private static final String PARTY = "ABC12";
    private static final String ENDED = "END12";
    private static final Path OUT = Path.of("target", "browser-harness");

    @TestConfiguration
    static class Sessions {
        @Bean
        @Order(Ordered.HIGHEST_PRECEDENCE + 50)
        SessionRepositoryFilter<MapSession> springSessionRepositoryFilter() {
            return new SessionRepositoryFilter<>(new MapSessionRepository(new ConcurrentHashMap<>()));
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PollService pollService;
    @MockitoBean
    private com.scan2play.service.LibraryHintService libraryHintService;
    @MockitoBean
    private com.scan2play.service.SiteCounter siteCounter;
    @MockitoBean
    private SongEvaluationService songEvaluationService;
    @MockitoBean
    private GuestQueueService guestQueueService;
    @MockitoBean
    private PartySettingsQueryService partySettingsQueryService;
    @MockitoBean
    private GuestSessionService guestSessionService;
    @MockitoBean
    private GuestRequestLimiter guestRequestLimiter;
    @MockitoBean
    private GuestVoteService guestVoteService;
    @MockitoBean
    private QrCodeService qrCodeService;

    private final PollService.Poll asked = new PollService.Poll(5, PARTY, "Disco polo — tak czy nie?", List.of("Tak", "Nie", "Tylko jedna"),
            12, Instant.now(), Instant.now(), null);
    private final PollService.Results results = new PollService.Results(asked, List.of(new PollService.Row(0, "Tak", 7, 58, true),
            new PollService.Row(1, "Nie", 4, 33, false), new PollService.Row(2, "Tylko jedna", 1, 8, false)), 12);

    @BeforeEach
    void setUp() {
        when(partySettingsQueryService.getSettings(PARTY)).thenReturn(PartySettingsEntity.builder().partyCode(PARTY).active(true)
                .globalVibe(com.scan2play.model.VibeType.ANY).build());
        when(partySettingsQueryService.getSettings(ENDED)).thenReturn(PartySettingsEntity.builder().partyCode(ENDED).active(false).build());
        when(guestQueueService.view(anyString(), any(), any()))
                .thenReturn(new GuestQueueService.GuestQueue(List.of(), List.of(), Set.of(), null, 0, Set.of()));
        when(guestQueueService.mostWanted(anyString())).thenReturn(List.of());
        when(guestRequestLimiter.clientIp(any())).thenReturn("203.0.113.7");
        when(guestRequestLimiter.tryAcquireVote(anyString(), anyString())).thenReturn(Optional.empty());
        when(pollService.shown(PARTY)).thenReturn(Optional.of(asked));
        when(pollService.results(asked)).thenReturn(results);
    }

    private static void write(String name, String html) throws IOException {
        Files.createDirectories(OUT);
        Files.writeString(OUT.resolve(name), html, StandardCharsets.UTF_8);
    }

    private static String csrfToken(String html) {
        Matcher token = Pattern.compile("id=\"pollForm\"[^>]*>\\s*<input type=\"hidden\" name=\"_csrf\" value=\"([^\"]+)\"").matcher(html);
        assertThat(token.find()).as("the question's form carries the CSRF token").isTrue();
        return token.group(1);
    }

    @Test
    void aFirstVisit_seesTheQuestionAboveTheForm_withItsToken_andTheWholePage() throws Exception {
        MvcResult result = mockMvc.perform(get("/p/" + PARTY).header("Accept-Language", "pl-PL")).andExpect(status().isOk()).andReturn();
        assertThat(result.getResolvedException()).isNull();
        String html = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(html).contains("id=\"guestPoll\"", ">🗳 Pytanie od DJ-a<", ">Disco polo — tak czy nie?<",
                "action=\"/p/ABC12/poll/5\"", "name=\"choice\" value=\"0\"", ">Tak<", ">Tylko jedna<", "/js/guest-poll.js", "</html>");
        csrfToken(html);
        assertThat(html.indexOf("id=\"guestPoll\"")).as("above the request form").isLessThan(html.indexOf("id=\"requestForm\""));
        assertThat(html).as("no results before the guest answers").doesNotContain("s2p-poll-bar");
        assertThat(result.getResponse().getHeaders("Set-Cookie")).anyMatch(cookie -> cookie.startsWith("SESSION="));
        write("guest-poll.html", html);
    }

    @Test
    void aGuestWhoAnswered_seesTheResults_withTheirAnswerMarked_andMayChangeIt() throws Exception {
        when(pollService.choiceOf(eq(5L), anyString())).thenReturn(Optional.of(1));
        String html = mockMvc.perform(get("/p/" + PARTY).header("Accept-Language", "pl-PL")).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(html).contains("s2p-poll-bar", "value=\"58\"", ">58%<", "(7)", ">Głosy: 12<", "✓ Twoja", "id=\"pollChangeForm\"",
                ">Zmień odpowiedź:<").doesNotContain("id=\"pollForm\"");
        // the other answers to change one's mind, not the one given
        String change = html.substring(html.indexOf("id=\"pollChangeForm\""));
        change = change.substring(0, change.indexOf("</form>"));
        assertThat(change).contains("value=\"0\"", "value=\"2\"").doesNotContain("value=\"1\"");
        write("guest-poll-answered.html", html);
    }

    @Test
    void anEndedQuestion_showsItsResultsToEveryone_withNoForm() throws Exception {
        PollService.Poll ended = new PollService.Poll(5, PARTY, asked.question(), asked.answers(), 12, Instant.now(), Instant.now(),
                Instant.now());
        when(pollService.shown(PARTY)).thenReturn(Optional.of(ended));
        when(pollService.results(ended)).thenReturn(new PollService.Results(ended, results.rows(), 12));
        String html = mockMvc.perform(get("/p/" + PARTY + "/poll").header("Accept-Language", "pl-PL")).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(html.strip()).startsWith("<section id=\"guestPoll\"");
        assertThat(html).contains(">🗳 Wyniki pytania<", ">58%<", "Pytanie zakończone.").doesNotContain("<form");
        write("guest-poll-card-ended.html", html);
    }

    @Test
    void nothingAsked_leavesAnEmptyHiddenPlace_andMakesNoSession() throws Exception {
        when(pollService.shown(PARTY)).thenReturn(Optional.empty());
        MvcResult result = mockMvc.perform(get("/p/" + PARTY + "/poll")).andExpect(status().isOk()).andReturn();
        String html = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(html).contains("id=\"guestPoll\"", "hidden", "data-url=\"/p/ABC12/poll\"").doesNotContain("<form");
        assertThat(result.getResponse().getHeaders("Set-Cookie")).noneMatch(cookie -> cookie.startsWith("SESSION="));
    }

    /** The DJ closed the requests to ask the room alone: the question on "DJ nie przyjmuje teraz próśb", with the session its form needs. */
    @Test
    void thePageOfAClosedParty_asksTheQuestion_andMakesTheSessionItsFormNeeds() throws Exception {
        PollService.Poll endedParty = new PollService.Poll(6, ENDED, "Kto pierwszy na parkiet?", List.of("Ania", "Kuba"), 0, Instant.now(),
                Instant.now(), null);
        when(pollService.shown(ENDED)).thenReturn(Optional.of(endedParty));
        when(pollService.results(endedParty)).thenReturn(new PollService.Results(endedParty, List.of(), 0));
        MvcResult result = mockMvc.perform(get("/p/" + ENDED).header("Accept-Language", "pl-PL")).andExpect(status().isOk()).andReturn();
        assertThat(result.getResolvedException()).isNull();
        String html = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(html).contains("id=\"partyEndedTitle\"", ">Kto pierwszy na parkiet?<", "action=\"/p/END12/poll/6\"", "/js/guest-poll.js",
                "<footer", "</html>");
        csrfToken(html);
        assertThat(result.getResponse().getHeaders("Set-Cookie")).anyMatch(cookie -> cookie.startsWith("SESSION="));
        write("guest-poll-ended.html", html);
    }

    @Test
    void anAnswer_needsNoLogin_andComesBackAsTheCardWithTheResults() throws Exception {
        MvcResult page = mockMvc.perform(get("/p/" + PARTY)).andReturn();
        Cookie session = page.getResponse().getCookie("SESSION");
        assertThat(session).isNotNull();
        String token = csrfToken(page.getResponse().getContentAsString(StandardCharsets.UTF_8));
        when(pollService.vote(eq(PARTY), eq(5L), anyString(), eq(2))).thenReturn(PollService.Voted.VOTED);
        when(pollService.choiceOf(eq(5L), anyString())).thenReturn(Optional.of(2));

        String card = mockMvc.perform(post("/p/" + PARTY + "/poll/5").cookie(session).param("choice", "2").param("_csrf", token)
                        .header("X-Requested-With", "fetch").header("Accept-Language", "pl-PL"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(card.strip()).startsWith("<section id=\"guestPoll\"");
        assertThat(card).contains("s2p-poll-bar", "✓ Twoja").doesNotContain("id=\"pollNote\"");
        verify(pollService).vote(eq(PARTY), eq(5L), anyString(), eq(2));
        write("guest-poll-answer.html", card);
    }

    @Test
    void anAnswerToAnEndedQuestion_saysSo() throws Exception {
        MvcResult page = mockMvc.perform(get("/p/" + PARTY)).andReturn();
        String token = csrfToken(page.getResponse().getContentAsString(StandardCharsets.UTF_8));
        when(pollService.vote(anyString(), anyLong(), anyString(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(PollService.Voted.NOT_ASKED);
        String card = mockMvc.perform(post("/p/" + PARTY + "/poll/5").cookie(page.getResponse().getCookie("SESSION")).param("choice", "0")
                        .param("_csrf", token).header("X-Requested-With", "fetch").header("Accept-Language", "pl-PL"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(card).contains("id=\"pollNote\"", "To pytanie jest już zakończone.");
    }

    @Test
    void anAnswerWithoutTheToken_isRefused_beforeAnythingIsCounted() throws Exception {
        mockMvc.perform(post("/p/" + PARTY + "/poll/5").session(new MockHttpSession()).param("choice", "0")
                .header("X-Requested-With", "fetch")).andExpect(status().is3xxRedirection());
        verify(pollService, never()).vote(anyString(), anyLong(), anyString(), org.mockito.ArgumentMatchers.anyInt());
    }

    /** The DJ keeps the results for a game's end (V75): no number of an answer anywhere in the guest's card, only how many voted. */
    @Test
    void resultsKeptForLater_areNotInTheGuestsCard_onlyTheirAnswerAndHowManyVoted() throws Exception {
        PollService.Poll hidden = new PollService.Poll(5, PARTY, "Kto pierwszy na parkiet?", List.of("Ania", "Kuba"), 23, Instant.now(),
                Instant.now(), null, true, null);
        when(pollService.shown(PARTY)).thenReturn(Optional.of(hidden));
        when(pollService.choiceOf(eq(5L), anyString())).thenReturn(Optional.of(1));
        String html = mockMvc.perform(get("/p/" + PARTY + "/poll").header("Accept-Language", "pl-PL")).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(html).contains(">🗳 Pytanie od DJ-a<", "id=\"pollMineHidden\"", ">Kuba<", "✓ Twoja", "Wyniki pokaże DJ.", ">Głosy: 23<",
                "data-poll-refresh", "↻ Sprawdź wyniki", "id=\"pollChangeForm\"");
        assertThat(html).as("no share, no bar, no count of an answer").doesNotContain("%", "s2p-poll-bar", "s2p-poll-lead", "(7)");
        verify(pollService, never()).results(any());
        write("guest-poll-hidden.html", mockMvc.perform(get("/p/" + PARTY).header("Accept-Language", "pl-PL")).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8));

        String tv = mockMvc.perform(get("/p/" + PARTY + "/tv/list").param("lang", "pl")).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(tv).contains(">Pytanie DJ-a<", "id=\"tvPollHidden\"", "Wyniki pokaże DJ", ">Głosy: 23<", ">Votes: 23<")
                .doesNotContain("%", "tv-poll-bar", "tv-poll-lead");
    }

    @Test
    void theTv_showsTheQuestionWithItsResults_inPlaceOfTheList() throws Exception {
        String html = mockMvc.perform(get("/p/" + PARTY + "/tv/list").param("lang", "pl")).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(html.strip()).startsWith("<section id=\"tvList\"");
        assertThat(html).contains(">Pytanie DJ-a<", ">The DJ asks<", ">Disco polo — tak czy nie?<", "Zeskanuj kod i odpowiedz na telefonie",
                "tv-poll-lead", ">58%<", "value=\"58\"", ">Głosy: 12<", ">Votes: 12<").doesNotContain("Najczęściej wybierane", "<form");
        assertThat(mockMvc.perform(get("/p/" + PARTY + "/tv/list")).andReturn().getResponse().getHeaders("Set-Cookie"))
                .as("the TV makes no session").noneMatch(cookie -> cookie.startsWith("SESSION="));
        when(qrCodeService.generateQrCodeBase64(anyString(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt()))
                .thenAnswer(call -> new QrCodeService().generateQrCodeBase64(call.getArgument(0), call.getArgument(1), call.getArgument(2)));
        write("tv-poll.html", mockMvc.perform(get("/p/" + PARTY + "/tv").param("lang", "pl")).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8));
    }
}
