package com.scan2play.template;

import com.scan2play.controller.DjSessionHelper;
import com.scan2play.controller.DjSummaryController;
import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.entity.SongRequestEntity;
import com.scan2play.repository.SongRequestRepository;
import com.scan2play.service.EveningSummaryService;
import com.scan2play.util.Times;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockServletContext;
import org.springframework.ui.ConcurrentModel;
import org.thymeleaf.context.WebContext;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import org.thymeleaf.web.servlet.JakartaServletWebApplication;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The evening summary page ({@code summary.html}, {@link DjSummaryController}): rendered through the real controller, the real
 * service (over a mocked repository) and the real message bundles. Written to {@code target/summary/} for a look in a browser.
 */
class SummaryPageTest {

    private static final String PARTY = "SUMM1";
    private static final LocalDate EVENING = LocalDate.of(2026, 10, 3);
    private static final Path OUT = Path.of("target", "summary");

    private static SpringTemplateEngine engine;
    private static ResourceBundleMessageSource messages;

    @BeforeAll
    static void setUpEngine() {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCharacterEncoding("UTF-8");

        messages = new ResourceBundleMessageSource();
        messages.setBasename("messages");
        messages.setDefaultEncoding("UTF-8");
        messages.setFallbackToSystemLocale(false);

        engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
        engine.setTemplateEngineMessageSource(messages);
    }

    private static Instant at(int hour, int minute) {
        LocalDate day = hour < 6 ? EVENING.plusDays(1) : EVENING;
        return day.atTime(LocalTime.of(hour, minute)).atZone(Times.DISPLAY_ZONE).toInstant();
    }

    private static SongRequestEntity request(String song, String decision, Instant asked, Instant played, int votes) {
        return SongRequestEntity.builder().partyCode(PARTY).songName(song).decision(decision).requestedAt(asked).playedAt(played)
                .votes(votes).build();
    }

    /** A wedding: requests from 19:00 to 01:00, most of them played, a few rejected by the AI, one waiting. */
    private static List<SongRequestEntity> wedding() {
        List<SongRequestEntity> requests = new ArrayList<>();
        requests.add(request("Varius Manx - Orła cień", "played", at(19, 5), at(19, 40), 6));
        SongRequestEntity zenek = request("Zenek Martyniuk - Przekorny los", "played", at(20, 15), at(20, 30), 9);
        zenek.setTips(3);
        requests.add(zenek);
        requests.add(request("Golec uOrkiestra - Ściernisko", "played", at(21, 2), at(21, 20), 4));
        requests.add(request("ABBA - Dancing Queen", "played", at(22, 10), at(22, 25), 7));
        requests.add(request("Bajm - Biała armia", "played", at(22, 30), at(23, 50), 2));
        requests.add(request("Nirvana - Smells Like Teen Spirit", "rejected", at(22, 45), null, 1));
        requests.add(request("Sanah - Szampan", "played", at(0, 20), at(0, 35), 5));
        requests.add(request("Lady Pank - Mniej niż zero", "accepted", at(0, 55), null, 1));
        return requests;
    }

    private static String render(Locale locale, String evening, List<EveningSummaryService.Evening> evenings,
                                 List<SongRequestEntity> requests, String file) throws IOException {
        SongRequestRepository repository = mock(SongRequestRepository.class);
        when(repository.findEvenings(eq(PARTY), anyInt())).thenReturn(evenings.stream()
                .map(e -> new Object[]{e.day().toString(), e.requests()}).toList());
        when(repository.findRequestedBetween(eq(PARTY), any(), any(), any())).thenReturn(requests);
        DjSessionHelper sessionHelper = mock(DjSessionHelper.class);
        when(sessionHelper.require(any(), any(), any(), any()))
                .thenReturn(PartySettingsEntity.builder().partyCode(PARTY).djName("DJ Koko").build());
        DjSummaryController controller = new DjSummaryController(new EveningSummaryService(repository), sessionHelper, messages);

        ConcurrentModel model = new ConcurrentModel();
        String view = controller.summary(evening, null, model, null, new MockHttpSession());
        assertThat(view).isEqualTo("summary");

        MockServletContext servletContext = new MockServletContext();
        WebContext context = new WebContext(JakartaServletWebApplication.buildApplication(servletContext)
                .buildExchange(new MockHttpServletRequest(servletContext), new MockHttpServletResponse()), locale);
        context.setVariables(model.asMap());
        String html = engine.process(view, context);
        Files.createDirectories(OUT);
        // the stylesheet and the logo by a relative path, so that the file can be opened straight from target/
        Files.writeString(OUT.resolve(file + ".html"), html
                .replace("/css/summary.css", "../../src/main/resources/static/css/summary.css")
                .replace("/js/summary.js", "../../src/main/resources/static/js/summary.js")
                .replace("/images/logo.svg", "../../src/main/resources/static/images/logo.svg"), StandardCharsets.UTF_8);
        return html;
    }

    private static final List<EveningSummaryService.Evening> TWO_EVENINGS = List.of(
            new EveningSummaryService.Evening(EVENING, 8), new EveningSummaryService.Evening(LocalDate.of(2026, 9, 26), 3));

    @Test
    void theLatestEvening_withItsCounts_theTopSongs_theHours_andTheSetlist() throws IOException {
        String html = render(Locale.forLanguageTag("pl"), null, TWO_EVENINGS, wedding(), "wedding-pl");

        assertThat(html).contains("<h1>Podsumowanie wieczoru</h1>", "sobota, 03.10.2026", "· 19:05–00:55", "🎧 DJ Koko");
        // the evenings to pick, the shown one selected
        assertThat(html).contains("<option value=\"2026-10-03\" selected=\"selected\">sobota, 03.10 (8)</option>",
                "<option value=\"2026-09-26\">sobota, 26.09 (3)</option>");
        assertThat(html).contains("href=\"/dj/summary/csv?evening=2026-10-03&amp;party=" + PARTY + "\"", "id=\"printBtn\"");
        // 8 requests, 6 played, the votes of the 7 the AI let through, 1 rejected by it, 1 waiting; no skipped, no cleared
        assertThat(html).contains("<strong>8</strong><span>Prośby</span>", "<strong>6</strong><span>Zagrane</span>",
                "<strong>34</strong><span>Głosy gości</span>", "<strong>1</strong><span>Odrzucone przez AI</span>",
                "<span>Czekają</span>").doesNotContain("Pominięte przez DJ-a", "Wyczyszczone z kolejką");
        // the most wanted first; the rejected song is not one of them
        assertThat(html.indexOf("Przekorny los")).isLessThan(html.indexOf("Dancing Queen"));
        String top = html.substring(html.indexOf("id=\"top\""), html.indexOf("id=\"missed\"")).replaceAll("\\s+", " ");
        // the tips the DJ counted (V28): a stat, and "💸 3" at the song
        assertThat(html).contains("id=\"tipsStat\"", "<strong>3</strong>", "Napiwki");
        assertThat(top).contains("<span class=\"tips\">💸 3</span>");
        assertThat(top).contains("👍 9", "<span class=\"played\" title=\"zagrana\"><span class=\"icon\">✓</span> <span class=\"label\">zagrana</span></span>")
                .doesNotContain("Smells Like Teen Spirit");
        // how long the played ones waited: 35, 15, 18, 15, 80 and 15 minutes
        assertThat(html).contains("Zagrane prośby czekały średnio 30 min (najdłużej 80 min).");
        // wanted, not heard: the one still waiting, with its status
        String missed = html.substring(html.indexOf("id=\"missed\""), html.indexOf("id=\"artists\"")).replaceAll("\\s+", " ");
        // the status: an icon and its word (a phone shows the icon alone, summary.css), the word as the title, a legend of the icons
        assertThat(missed).contains("Lady Pank - Mniej niż zero",
                "<span class=\"status\" title=\"czeka\"><span class=\"icon\">⏳</span> <span class=\"label\">czeka</span></span>",
                "<p class=\"legend\">⏳ czeka · ⏭ pominięta przez DJ-a · 🧹 wyczyszczona z kolejką</p>").doesNotContain("Szampan");
        // the artists: Zenek's 9 votes first
        String artists = html.substring(html.indexOf("id=\"artists\""), html.indexOf("id=\"slots\""));
        assertThat(artists.indexOf("Zenek Martyniuk")).isLessThan(artists.indexOf("ABBA"));
        assertThat(artists).contains("piosenek: 1").doesNotContain("Nirvana");
        // the half-hours from 19:00 to 00:30, the busiest one named
        assertThat(html).contains("<th>19:00</th>", "<th>19:30</th>", "<th>00:30</th>", "Najwięcej: 22:30–23:00")
                .doesNotContain("<th>01:00</th>");
        // the setlist in the order played, with the time each played
        String setlist = html.substring(html.indexOf("id=\"setlist\""));
        assertThat(setlist.indexOf("Orła cień")).isLessThan(setlist.indexOf("Biała armia"));
        assertThat(setlist.indexOf("Biała armia")).isLessThan(setlist.indexOf("Szampan"));
        assertThat(setlist).contains("<span class=\"time\">23:50</span>").doesNotContain("Mniej niż zero");
        assertThat(html).doesNotContain("??", "id=\"noEvenings\"", "id=\"emptyEvening\"");
    }

    @Test
    void inEnglish() throws IOException {
        String html = render(Locale.ENGLISH, "2026-10-03", TWO_EVENINGS, wedding(), "wedding-en");

        assertThat(html).contains("<h1>Evening summary</h1>", "Saturday, 03.10.2026", "Most wanted", "Busiest: 22:30–23:00", "Wanted, not heard", "Most wanted artists", "songs: 1",
                "Played, in order").doesNotContain("??");
    }

    @Test
    void aPartyWithoutRequests_saysSo_andOffersNothingToPrint() throws IOException {
        String html = render(Locale.forLanguageTag("pl"), null, List.of(), List.of(), "none");

        assertThat(html).contains("id=\"noEvenings\"", "Nie ma jeszcze próśb gości")
                .doesNotContain("id=\"printBtn\"", "/dj/summary/csv", "id=\"eveningSelect\"", "id=\"stats\"", "??");
    }

    @Test
    void anEveningWithoutRequests_saysSo() throws IOException {
        String html = render(Locale.forLanguageTag("pl"), "2026-08-01", TWO_EVENINGS, List.of(), "empty");

        assertThat(html).contains("id=\"emptyEvening\"", "Tego wieczoru nie było próśb").doesNotContain("id=\"stats\"");
    }

    @Test
    void theCsv_isAFileOfTheEvening_inTheDjsLanguage() {
        SongRequestRepository repository = mock(SongRequestRepository.class);
        when(repository.findEvenings(eq(PARTY), anyInt())).thenReturn(List.<Object[]>of(new Object[]{"2026-10-03", 8L}));
        when(repository.findRequestedBetween(eq(PARTY), any(), any(), any())).thenReturn(wedding());
        DjSessionHelper sessionHelper = mock(DjSessionHelper.class);
        when(sessionHelper.require(any(), any(), any(), any())).thenReturn(PartySettingsEntity.builder().partyCode(PARTY).build());
        DjSummaryController controller = new DjSummaryController(new EveningSummaryService(repository), sessionHelper, messages);

        ResponseEntity<byte[]> csv = controller.csv(null, null, Locale.ENGLISH, null, new MockHttpSession());

        assertThat(csv.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .isEqualTo("attachment; filename=\"scan2play-2026-10-03.csv\"");
        assertThat(csv.getHeaders().getContentType()).hasToString("text/csv;charset=UTF-8");
        String text = new String(csv.getBody(), StandardCharsets.UTF_8);
        assertThat(text.split("\r\n")).hasSize(9);
        assertThat(text).contains("Number;Requested;Played;Song;Guest wrote;Votes;Tips;Status;AI comment",
                "\"rejected by the AI\"");

        when(repository.findEvenings(eq(PARTY), anyInt())).thenReturn(List.of());
        assertThat(controller.csv(null, null, Locale.ENGLISH, null, new MockHttpSession()).getStatusCode().value()).isEqualTo(404);
    }
}
