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
 * service (over a mocked repository) and the real message bundles. Written to {@code target/summary/} for a look in a browser, and
 * as {@code summary-<name>.html} to {@code target/browser-harness/} for the browser tests ({@code src/test/browser}, scenarios
 * {@code summary-*}).
 */
class SummaryPageTest {

    private static final String PARTY = "SUMM1";
    private static final LocalDate EVENING = LocalDate.of(2026, 10, 3);
    private static final Path OUT = Path.of("target", "summary");
    private static final Path HARNESS = Path.of("target", "browser-harness");

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
        SongRequestEntity nirvana = request("Nirvana - Smells Like Teen Spirit", "rejected", at(22, 45), null, 1);
        nirvana.setRejectReason(com.scan2play.model.RejectReason.OFF_VIBE);
        requests.add(nirvana);
        requests.add(request("Sanah - Szampan", "played", at(0, 20), at(0, 35), 5));
        requests.add(request("Lady Pank - Mniej niż zero", "accepted", at(0, 55), null, 1));
        return requests;
    }

    /**
     * A long wedding: 60 songs asked for from 19:00 to 02:00, 40 of them played (names up to "Dawid Podsiadło - Szczęśliwej drogi,
     * już czas"), 8 rejected by the AI, 4 skipped, 8 waiting — what the hosts' version must fit on one A4 page.
     */
    private static List<SongRequestEntity> longWedding() {
        String[] artists = {"Zenek Martyniuk", "Sanah", "Dawid Podsiadło", "Golec uOrkiestra", "Kwiat Jabłoni", "Daria Zawiałow",
                "Maryla Rodowicz", "Varius Manx", "Lady Pank", "Kombii"};
        String[] titles = {"Przekorny los", "Szampan", "Małomiasteczkowy", "Ściernisko", "Dziś późno pójdę spać", "Kinga",
                "Niech żyje bal", "Orła cień", "Mniej niż zero", "Pokolenie", "Ale jazz!", "Nie ma fal", "Wszystko czego dziś chcę",
                "Szczęśliwej drogi, już czas", "Jak zapomnieć", "Królowa łez", "Ta ostatnia niedziela", "Sto lat"};
        List<SongRequestEntity> requests = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            int minutes = 19 * 60 + i * 7;   // 19:00 … 01:53
            Instant asked = at(minutes / 60 % 24, minutes % 60);
            String name = artists[i % artists.length] + " - " + titles[i % titles.length];   // 60 different pairs
            String decision = i < 40 ? "played" : i < 48 ? "rejected" : "accepted";
            SongRequestEntity request = request(name, decision, asked, i < 40 ? asked.plusSeconds(600L + i * 20L) : null, 1 + (i * 7) % 9);
            if (i >= 48 && i < 52) {
                request.setDecision("rejected");
                request.setSkippedAt(asked.plusSeconds(300));
            }
            requests.add(request);
        }
        return requests;
    }

    /** Who plays and the DJ's logo (V45): of every test but the guide's picture, which shows the party without a logo. */
    private static String djName = "DJ Koko";
    private static String logoVersion = "0123456789abcdef".repeat(4);

    /** The evenings' titles, links and kept songs (V61): none, unless a test gives some. */
    private static com.scan2play.repository.EveningSummaryRepository kept = mock(com.scan2play.repository.EveningSummaryRepository.class);

    /** The party's library (V42): none, unless a test gives one. */
    private static com.scan2play.service.DjLibraryService libraries = mock(com.scan2play.service.DjLibraryService.class);

    /** A library that has "Sanah - Szampan" and a "Biała armia" of another artist; nothing else of the wedding's. */
    private static com.scan2play.service.DjLibraryService aLibrary() {
        com.scan2play.service.DjLibraryService library = mock(com.scan2play.service.DjLibraryService.class);
        when(library.info(PARTY)).thenReturn(java.util.Optional.of(new com.scan2play.service.DjLibraryService.Info(2,
                com.scan2play.service.DjLibraryParser.Format.M3U, java.time.Instant.now(), null, true)));
        when(library.marks(eq(PARTY), org.mockito.ArgumentMatchers.anyCollection())).thenAnswer(call -> {
            java.util.Map<com.scan2play.util.LibraryKey, com.scan2play.model.LibraryMark> marks = new java.util.HashMap<>();
            for (Object key : (java.util.Collection<?>) call.getArgument(1)) {
                com.scan2play.util.LibraryKey asked = (com.scan2play.util.LibraryKey) key;
                marks.put(asked, asked.equals(com.scan2play.util.LibraryKey.of("Sanah - Szampan")) ? com.scan2play.model.LibraryMark.HAVE
                        : asked.title().equals("bialaarmia") ? com.scan2play.model.LibraryMark.SIMILAR : com.scan2play.model.LibraryMark.MISSING);
            }
            return marks;
        });
        return library;
    }

    /** The DJ's logo (V45, Pro) heads the summary, above ours. */
    @Test
    void theDjsLogo_headsTheSummary() throws IOException {
        String html = render(Locale.forLanguageTag("pl"), null, List.of(), List.of(), "summary-logo");
        // our logo at the foot
        assertThat(html).contains("class=\"dj-logo\"", "src=\"/p/" + PARTY + "/logo.png?v=0123456789abcdef\"");
        assertThat(html.indexOf("class=\"dj-logo\"")).isLessThan(html.indexOf("class=\"logo\""));
        assertThat(html.indexOf("class=\"logo\"")).isGreaterThan(html.indexOf("<footer"));
    }

    private static String render(Locale locale, String evening, List<EveningSummaryService.Evening> evenings,
                                 List<SongRequestEntity> requests, String file) throws IOException {
        return render(locale, evening, null, evenings, requests, file);
    }

    private static String render(Locale locale, String evening, String view, List<EveningSummaryService.Evening> evenings,
                                 List<SongRequestEntity> requests, String file) throws IOException {
        SongRequestRepository repository = mock(SongRequestRepository.class);
        when(repository.findEvenings(eq(PARTY), anyInt())).thenReturn(evenings.stream()
                .map(e -> new Object[]{e.day().toString(), e.votes()}).toList());
        when(repository.findRequestedBetween(eq(PARTY), any(), any(), any())).thenReturn(requests);
        DjSessionHelper sessionHelper = mock(DjSessionHelper.class);
        when(sessionHelper.require(any(), any(), any(), any()))
                .thenReturn(PartySettingsEntity.builder().partyCode(PARTY).djName(djName).logoVersion(logoVersion).build());
        DjSummaryController controller = new DjSummaryController(new EveningSummaryService(repository, libraries, kept), sessionHelper, messages, com.scan2play.service.Plans.off(), "https://www.scan2play.com.pl");

        ConcurrentModel model = new ConcurrentModel();
        String template = controller.summary(evening, null, view, model, null, new MockHttpSession());
        assertThat(template).isEqualTo("summary");

        return process(locale, model, file);
    }

    /** The summary template with {@code model}, written as {@code file} for a look and for the browser tests. */
    private static String process(Locale locale, ConcurrentModel model, String file) throws IOException {
        MockServletContext servletContext = new MockServletContext();
        WebContext context = new WebContext(JakartaServletWebApplication.buildApplication(servletContext)
                .buildExchange(new MockHttpServletRequest(servletContext), new MockHttpServletResponse()), locale);
        context.setVariables(model.asMap());
        String html = engine.process("summary", context);
        Files.createDirectories(HARNESS);
        Files.writeString(HARNESS.resolve("summary-" + file + ".html"), html, StandardCharsets.UTF_8);
        Files.createDirectories(OUT);
        // the stylesheet and the logo by a relative path, so that the file can be opened straight from target/
        Files.writeString(OUT.resolve(file + ".html"), html
                .replace("/css/summary.css", "../../src/main/resources/static/css/summary.css")
                .replace("/js/summary.js", "../../src/main/resources/static/js/summary.js")
                .replace("/images/logo-small.svg", "../../src/main/resources/static/images/logo-small.svg"), StandardCharsets.UTF_8);
        return html;
    }

    private static final List<EveningSummaryService.Evening> TWO_EVENINGS = List.of(
            new EveningSummaryService.Evening(EVENING, 34), new EveningSummaryService.Evening(LocalDate.of(2026, 9, 26), 3));

    @Test
    void theLatestEvening_withItsCounts_theTopSongs_theHours_andTheSetlist() throws IOException {
        String html = render(Locale.forLanguageTag("pl"), null, TWO_EVENINGS, wedding(), "wedding-pl");

        assertThat(html).contains("<h1>Podsumowanie wieczoru</h1>", "sobota, 03.10.2026", "· prośby 19:05–00:55", "🎧 DJ Koko");
        // the evenings to pick with their guests' votes, the shown one selected
        assertThat(html).contains("<option value=\"2026-10-03\" selected=\"selected\">sobota, 03.10 · 👍 34</option>",
                "<option value=\"2026-09-26\">sobota, 26.09 · 👍 3</option>");
        // by month: 13 months of evenings are kept, a long list stays one to find a month in
        assertThat(html).contains("<optgroup label=\"październik 2026\">", "<optgroup label=\"wrzesień 2026\">");
        assertThat(html.indexOf("październik 2026")).isLessThan(html.indexOf("wrzesień 2026"));
        // the hosts' panel is the hosts' version's
        assertThat(html).doesNotContain("id=\"hostsPanel\"", "name=\"robots\"");
        assertThat(html).contains("href=\"/dj/summary/csv?evening=2026-10-03&amp;party=" + PARTY + "\"", "id=\"printBtn\"");
        // the version: the DJ's lit, the hosts' a link with view=hosts
        assertThat(html).contains("aria-current=\"page\" class=\"on\">Dla mnie</a>",
                "href=\"/dj/summary?evening=2026-10-03&amp;party=" + PARTY + "&amp;view=hosts\" id=\"viewHosts\">Dla gospodarzy</a>")
                .doesNotContain("id=\"hostsHint\"");
        // four counts: 6 played, the votes of the 7 songs the AI let through, 1 rejected by it; the rest in one line
        assertThat(html).contains("<strong>6</strong><span>Zagrane</span>", "<strong>34</strong><span>Głosy gości</span>",
                "<strong>7</strong><span>Piosenek chcianych</span>", "<strong>1</strong><span>Odrzucone przez AI</span>");
        assertThat(html).contains("<span id=\"waitingCount\">czekają: 1</span>", "<span id=\"tipsStat\">💸 napiwki: 3</span>")
                .doesNotContain("Pominięte", "pominięte przez DJ-a:", "wyczyszczone z kolejką:", "id=\"dedicationsStat\"");
        // the most wanted first; the rejected song is not one of them
        assertThat(html.indexOf("Przekorny los")).isLessThan(html.indexOf("Dancing Queen"));
        String top = html.substring(html.indexOf("id=\"top\""), html.indexOf("id=\"missed\"")).replaceAll("\\s+", " ");
        assertThat(top).contains("<span class=\"tips\">💸 3</span>");
        assertThat(top).contains("👍 9", "<span class=\"played\" title=\"zagrana\"><span class=\"icon\">✓</span> <span class=\"label\">zagrana</span></span>")
                .doesNotContain("Smells Like Teen Spirit");
        // how long the played ones waited: 35, 15, 18, 15, 80 and 15 minutes
        assertThat(html).contains("Zagrane prośby czekały średnio 30 min (najdłużej 80 min).");
        // wanted, not heard: the one still waiting, with its status
        String missed = html.substring(html.indexOf("id=\"missed\""), html.indexOf("id=\"slots\"")).replaceAll("\\s+", " ");
        // the status: an icon and its word (a phone shows the icon alone, summary.css), the word as the title, a legend of the icons
        assertThat(missed).contains("Lady Pank - Mniej niż zero",
                "<span class=\"status\" title=\"czeka\"><span class=\"icon\">⏳</span> <span class=\"label\">czeka</span></span>",
                "<p class=\"legend\">⏳ czeka · ⏭ pominięta przez DJ-a · 🧹 wyczyszczona z kolejką</p>").doesNotContain("Szampan");
        // every artist of one song: no list of artists
        assertThat(html).doesNotContain("id=\"artists\"");
        // why the AI rejected (V62): under its count, and the song it rejected with its reason
        assertThat(html).contains("<small id=\"rejectSplit\">już grały: 0 · nie pasowały: 1</small>");
        String rejected = html.substring(html.indexOf("id=\"rejected\""), html.indexOf("id=\"slots\"")).replaceAll("\\s+", " ");
        assertThat(rejected).contains(">Odrzucone przez AI<", "Nirvana - Smells Like Teen Spirit",
                "<span class=\"status\" title=\"nie pasowała do klimatu\"><span class=\"icon\">✗</span>", "próśb: 1");
        // "Ostatnie wieczory": the two evenings, this one marked, each leading to its summary; not printed
        String compare = html.substring(html.indexOf("id=\"compare\""));
        assertThat(compare).contains("class=\"no-print\"", ">Ostatnie wieczory<", "<tr class=\"shown\">",
                "href=\"/dj/summary?evening=2026-09-26&amp;party=" + PARTY + "\"", "aria-current=\"page\"");
        assertThat(html).doesNotContain("summary-social.js", "id=\"summarySocial\"");
        // the half-hours from 19:00 to 00:30: twelve bars, the busiest in the accent, a label every two hours
        String slots = html.substring(html.indexOf("id=\"slots\""), html.indexOf("id=\"setlist\""));
        assertThat(slots).contains(">Nowe prośby co pół godziny<", "Najwięcej: 22:30–23:00 (2)", "viewBox=\"0 0 120 60\"",
                "<title>22:30 · 2</title>", "<span>19:00</span>", "<span>21:00</span>", "<span>23:00</span>",
                "Pierwsza prośba o każdą piosenkę");
        assertThat(slots.split("<rect", -1)).hasSize(13);
        assertThat(slots.split("busiest-bar", -1)).hasSize(2);
        // the setlist in the order played, with the time each played, folded on a phone (summary.js)
        String setlist = html.substring(html.indexOf("id=\"setlist\""));
        assertThat(setlist).contains("<details id=\"setlistFold\" open>", ">Zagrane po kolei (6)<");
        assertThat(setlist.indexOf("Orła cień")).isLessThan(setlist.indexOf("Biała armia"));
        assertThat(setlist.indexOf("Biała armia")).isLessThan(setlist.indexOf("Szampan"));
        assertThat(setlist).contains("<span class=\"time\">23:50</span>").doesNotContain("Mniej niż zero");
        assertThat(html).doesNotContain("??", "id=\"noEvenings\"", "id=\"emptyEvening\"");
    }

    /**
     * A song asked for again after it played (out of the AI's duplicate window) is one line of the most wanted with the votes of
     * both, not "wanted, not heard"; an artist of two songs is listed.
     */
    @Test
    void aSongAskedForAgain_isOneLine() throws IOException {
        List<SongRequestEntity> requests = new ArrayList<>(wedding());
        SongRequestEntity again = request("Zenek Martyniuk - Przekorny los", "rejected", at(1, 30), null, 4);
        again.setClearedAt(at(1, 50));
        requests.add(again);
        requests.add(request("Sanah - Ale jazz!", "accepted", at(1, 40), null, 1));

        String html = render(Locale.forLanguageTag("pl"), null, TWO_EVENINGS, requests, "again-pl");

        String top = html.substring(html.indexOf("id=\"top\""), html.indexOf("id=\"missed\""));
        assertThat(top.split("Przekorny los", -1)).as("once").hasSize(2);
        assertThat(top).contains("👍 13");
        String missed = html.substring(html.indexOf("id=\"missed\""), html.indexOf("id=\"artists\""));
        assertThat(missed).contains("Ale jazz!").doesNotContain("Przekorny los");
        String artists = html.substring(html.indexOf("id=\"artists\""), html.indexOf("id=\"slots\""));
        assertThat(artists).contains(">Wykonawcy kilku piosenek<", ">Sanah<", "piosenek: 2").doesNotContain("Zenek");
        assertThat(html).contains("<span id=\"clearedCount\">wyczyszczone z kolejką: 1</span>");
    }

    /**
     * The hosts' version (view=hosts): what to give the couple or the client — three big numbers, the first and the last song, the
     * most wanted, when the guests asked, what played; nothing of what is the DJ's own (the AI's rejections, the skipped, the wanted
     * ones that did not play, the library, the tips, the votes of each played song).
     */
    @Test
    void theHostsVersion_leavesOutWhatIsTheDjsOwn() throws IOException {
        libraries = aLibrary();
        try {
            String html = render(Locale.forLanguageTag("pl"), null, "hosts", TWO_EVENINGS, wedding(), "hosts-pl");

            assertThat(html).contains("aria-current=\"page\" class=\"on\">Dla gospodarzy</a>", "id=\"viewDj\">Dla mnie</a>", "id=\"hostsHint\"",
                    "<input type=\"hidden\" name=\"view\" value=\"hosts\">", "<main class=\"hosts\">");
            assertThat(html).contains("<strong>34</strong><span>razy goście poprosili o piosenkę albo dali jej 👍</span>",
                    "<strong>6</strong><span>piosenek zagranych na życzenie</span>",
                    "<strong>22:30</strong>", "najwięcej próśb, 22:30–23:00");
            String firstLast = html.substring(html.indexOf("id=\"firstLast\""), html.indexOf("id=\"top\""));
            assertThat(firstLast).contains("Pierwsza prośba, 19:05", "Varius Manx - Orła cień", "Ostatnia zagrana, 00:35", "Sanah - Szampan");
            assertThat(html).contains(">Najczęściej proszone<", ">Kiedy goście prosili<", ">Zagrane na życzenie, po kolei (6)<");
            assertThat(html).doesNotContain("Odrzucone przez AI", "Głosy gości", "💸", "id=\"missed\"", "id=\"libraryGaps\"",
                    "id=\"artists\"", "id=\"moreCounts\"", "id=\"wait\"", "Nirvana", "class=\"played\"", "class=\"tips\"",
                    "· prośby 19:05", "Pierwsza prośba o każdą piosenkę", "??");
            String setlist = html.substring(html.indexOf("id=\"setlist\""));
            assertThat(setlist).doesNotContain("👍");
        } finally {
            libraries = mock(com.scan2play.service.DjLibraryService.class);
        }
    }

    /** The long wedding for the browser tests: the hosts' version printed (one A4 page), the DJ's on a phone. */
    @Test
    void aLongWedding_forTheBrowserTests() throws IOException {
        String hosts = render(Locale.forLanguageTag("pl"), null, "hosts", TWO_EVENINGS, longWedding(), "long-hosts-pl");
        String dj = render(Locale.forLanguageTag("pl"), null, null, TWO_EVENINGS, longWedding(), "long-pl");

        assertThat(hosts).contains(">Zagrane na życzenie, po kolei (40)<").doesNotContain("??");
        assertThat(dj).contains(">Zagrane po kolei (40)<", "<strong>8</strong><span>Odrzucone przez AI</span>",
                "<span id=\"skippedCount\">pominięte przez DJ-a: 4</span>").doesNotContain("??");
    }

    /**
     * The hosts' version on the DJ's page (V61): the panel to name the evening and make its hosts' link — without a link "Utwórz link
     * dla gospodarzy"; with one, the address to copy, the day it stops, "Nowy link" and "Wyłącz link" (asked first). The DJ's title
     * heads the page, "Podsumowanie wieczoru" small above it.
     */
    @Test
    void theHostsPanel_namesTheEvening_andMakesItsLink() throws IOException {
        String none = render(Locale.forLanguageTag("pl"), null, "hosts", TWO_EVENINGS, wedding(), "hosts-panel-pl");
        assertThat(none).contains("id=\"hostsPanel\"", ">Dla gospodarzy<", "action=\"/dj/summary/title\"", "name=\"title\" maxlength=\"60\"",
                "placeholder=\"np. Wesele Ani i Tomka\"", "id=\"shareMake\">Utwórz link dla gospodarzy<", "<h1>Podsumowanie wieczoru</h1>",
                // "Do internetu": the picture's words from the panel, its script on the hosts' version only
                "id=\"summarySocial\" hidden", "data-name=\"scan2play-2026-10-03\"", "data-top-label=\"Top 5 parkietu\"",
                "data-made=\"Działa na\"", "id=\"summaryStory\">Story (9:16)<", "/js/summary-social.js")
                .doesNotContain("id=\"shareLinkInput\"", "class=\"kind\"", "id=\"compare\"", "id=\"rejected\"", "id=\"rejectSplit\"");

        kept = mock(com.scan2play.repository.EveningSummaryRepository.class);
        try {
            when(kept.findByPartyCodeAndEvening(PARTY, EVENING)).thenReturn(java.util.Optional.of(com.scan2play.entity.EveningSummaryEntity.builder()
                    .partyCode(PARTY).evening(EVENING).title("Wesele Ani i Tomka").shareToken("aBcD1234aBcD1234aBcD12")
                    .shareExpiresAt(Instant.now().plus(java.time.Duration.ofDays(20))).updatedAt(Instant.now()).build()));
            String linked = render(Locale.forLanguageTag("pl"), null, "hosts", TWO_EVENINGS, wedding(), "hosts-linked-pl");

            assertThat(linked).contains("<title>Wesele Ani i Tomka · Podsumowanie wieczoru</title>", "<p class=\"kind\">Podsumowanie wieczoru</p>",
                    "<h1>Wesele Ani i Tomka</h1>", "value=\"Wesele Ani i Tomka\"",
                    "id=\"shareLinkInput\" readonly value=\"https://www.scan2play.com.pl/s/aBcD1234aBcD1234aBcD12\"",
                    "data-copy-target=\"shareLinkInput\"", "id=\"shareUntil\">Działa do ", ">Nowy link<", "action=\"/dj/summary/share/off\"",
                    "data-confirm=\"Wyłączyć link? Gospodarze nie otworzą już podsumowania.\"")
                    .doesNotContain("id=\"shareMake\"");
            // the DJ's version: the title, no panel
            String dj = render(Locale.forLanguageTag("pl"), null, null, TWO_EVENINGS, wedding(), "linked-pl");
            assertThat(dj).contains("<h1>Wesele Ani i Tomka</h1>").doesNotContain("id=\"hostsPanel\"");
        } finally {
            kept = mock(com.scan2play.repository.EveningSummaryRepository.class);
        }
    }

    /**
     * The requests refused before the AI (V63): counted, never saved — one line in the DJ's version, by reason; none in the hosts'.
     */
    @Test
    void theRefusalsBeforeTheAi_areOneLine_ofTheDjsVersion() throws IOException {
        kept = mock(com.scan2play.repository.EveningSummaryRepository.class);
        try {
            when(kept.findRefusals(PARTY, EVENING)).thenReturn(List.<Object[]>of(new Object[]{"NOT_A_SONG", 4}, new Object[]{"HOSTS_BLOCKED", 3}));
            String dj = render(Locale.forLanguageTag("pl"), null, null, TWO_EVENINGS, wedding(), "refusals-pl").replaceAll("\\s+", " ");
            assertThat(dj).contains("<p class=\"counts\" id=\"refusals\"><strong>Zatrzymane, zanim zapytano AI:</strong>",
                    "<span>z listy „nie grać” gospodarzy: 3</span>", "<span>nie piosenka (nastrój, wiadomość): 4</span>")
                    .doesNotContain("niedawno pominięte przez Ciebie");
            assertThat(dj.indexOf("gospodarzy: 3")).as("in the reasons' order").isLessThan(dj.indexOf("nie piosenka"));
            assertThat(render(Locale.forLanguageTag("pl"), null, "hosts", TWO_EVENINGS, wedding(), "refusals-hosts-pl"))
                    .doesNotContain("id=\"refusals\"");
            assertThat(render(Locale.ENGLISH, null, null, TWO_EVENINGS, wedding(), "refusals-en"))
                    .contains("Stopped before the AI:", "on the hosts’ do-not-play list: 3", "not a song (a mood, a message): 4");
        } finally {
            kept = mock(com.scan2play.repository.EveningSummaryRepository.class);
        }
        assertThat(render(Locale.forLanguageTag("pl"), null, null, TWO_EVENINGS, wedding(), "wedding-pl")).doesNotContain("id=\"refusals\"");
    }

    /**
     * The hosts' link (V61, {@code /s/{token}}): the hosts' version alone — no bar, no DJ's panel, no DJ's script, never indexed;
     * an unknown or old link a 404.
     */
    @Test
    void theHostsLink_opensTheHostsVersionAlone() throws IOException {
        kept = mock(com.scan2play.repository.EveningSummaryRepository.class);
        try {
            when(kept.findByShareToken("aBcD1234aBcD1234aBcD12")).thenReturn(java.util.Optional.of(com.scan2play.entity.EveningSummaryEntity.builder()
                    .partyCode(PARTY).evening(EVENING).title("Wesele Ani i Tomka").shareToken("aBcD1234aBcD1234aBcD12")
                    .shareExpiresAt(Instant.now().plus(java.time.Duration.ofDays(20))).updatedAt(Instant.now()).build()));
            when(kept.findByShareToken("old")).thenReturn(java.util.Optional.of(com.scan2play.entity.EveningSummaryEntity.builder()
                    .partyCode(PARTY).evening(EVENING).shareToken("old").shareExpiresAt(Instant.now().minusSeconds(1)).updatedAt(Instant.now()).build()));
            com.scan2play.repository.PartySettingsRepository parties = mock(com.scan2play.repository.PartySettingsRepository.class);
            when(parties.findByPartyCode(PARTY)).thenReturn(java.util.Optional.of(PartySettingsEntity.builder().partyCode(PARTY).djName("DJ Koko").build()));
            com.scan2play.controller.SharedSummaryController controller = new com.scan2play.controller.SharedSummaryController(
                    new EveningSummaryService(repositoryOf(wedding()), libraries, kept), parties, com.scan2play.service.Plans.off());

            ConcurrentModel model = new ConcurrentModel();
            MockHttpServletResponse response = new MockHttpServletResponse();
            assertThat(controller.shared("aBcD1234aBcD1234aBcD12", model, response)).isEqualTo("summary");
            assertThat(response.getHeader("Referrer-Policy")).isEqualTo("no-referrer");
            assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
            String html = process(Locale.forLanguageTag("pl"), model, "shared-pl");

            assertThat(html).contains("<meta name=\"robots\" content=\"noindex, nofollow\">", "<h1>Wesele Ani i Tomka</h1>",
                    "<main class=\"hosts\">", "<strong>34</strong><span>razy goście poprosili", ">Zagrane na życzenie, po kolei (6)<",
                    "/js/summary.js")
                    .doesNotContain("class=\"toolbar", "id=\"hostsPanel\"", "/js/dj-nav.js", "summary-social.js", "id=\"compare\"",
                            "id=\"noEvenings\"", "Odrzucone przez AI",
                            "💸", "/dj/", "??");
            for (String token : new String[]{"old", "unknown", "x".repeat(33)}) {
                org.assertj.core.api.Assertions.assertThatThrownBy(() -> controller.shared(token, new ConcurrentModel(), new MockHttpServletResponse()))
                        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class).hasMessageContaining("404");
            }
        } finally {
            kept = mock(com.scan2play.repository.EveningSummaryRepository.class);
        }
    }

    @Test
    void inEnglish() throws IOException {
        String html = render(Locale.ENGLISH, "2026-10-03", TWO_EVENINGS, wedding(), "wedding-en");

        assertThat(html).contains("<h1>Evening summary</h1>", "Saturday, 03.10.2026", "· requests 19:05–00:55", "Most wanted",
                "Busiest: 22:30–23:00 (2)", "Wanted, not heard", "New requests every half-hour", "Played, in order (6)", "For me",
                "For the hosts", "Songs wanted").doesNotContain("??");
        assertThat(render(Locale.ENGLISH, "2026-10-03", "hosts", TWO_EVENINGS, wedding(), "hosts-en"))
                .contains("When the guests asked", "Played on request, in order (6)", "The first request, 19:05",
                        // the picture's foot, as the poster's: "Powered by Scan2Play"
                        "data-made=\"Powered by\"").doesNotContain("??");
    }

    /**
     * The evening summary of a made-up wedding, for the guide's picture (src/test/browser/screenshots.py:
     * {@code static/images/guide/summary-<lang>.webp}): the DJ of the landing page's picture, no logo, and in English songs known
     * everywhere — as the landing page's dashboard. No real person in it.
     */
    @Test
    void theGuidesPicture_ofTheSummary() throws IOException {
        String[] english = {"Whitney Houston - I Wanna Dance with Somebody", "Bon Jovi - Livin' on a Prayer", "Ed Sheeran - Perfect",
                "ABBA - Dancing Queen", "Queen - Don't Stop Me Now", "Nirvana - Smells Like Teen Spirit", "Mark Ronson - Uptown Funk",
                "Earth, Wind & Fire - September"};
        String savedDj = djName;
        String savedLogo = logoVersion;
        try {
            logoVersion = null;
            djName = "DJ Kuba";
            String pl = render(Locale.forLanguageTag("pl"), null, TWO_EVENINGS, wedding(), "guide-demo-pl");
            djName = "DJ Alex";
            List<SongRequestEntity> weddingEn = wedding();
            assertThat(english).as("a song in English for each of the wedding's").hasSize(weddingEn.size());
            for (int i = 0; i < weddingEn.size(); i++) {
                weddingEn.get(i).setSongName(english[i]);
            }
            String en = render(Locale.ENGLISH, null, TWO_EVENINGS, weddingEn, "guide-demo-en");

            assertThat(pl).contains("🎧 DJ Kuba", "<strong>6</strong><span>Zagrane</span>", "Zenek Martyniuk - Przekorny los")
                    .doesNotContain("DJ Koko", "??");
            assertThat(en).contains("🎧 DJ Alex", "<strong>6</strong><span>Played</span>", "Bon Jovi - Livin&#39; on a Prayer")
                    .doesNotContain("DJ Koko", "Zenek", "Sanah", "Golec", "Bajm", "??");
        } finally {
            djName = savedDj;
            logoVersion = savedLogo;
        }
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
        DjSummaryController controller = new DjSummaryController(new EveningSummaryService(repository, libraries, kept), sessionHelper, messages, com.scan2play.service.Plans.off(), "https://www.scan2play.com.pl");

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

    /**
     * The DJ's library (V42): "Nie masz w bibliotece" — what the AI let through and the library has not ("✗") or has only by another
     * artist ("?"), the most wanted first; what the DJ has and what the AI rejected not there. By the library as it is now.
     */
    @Test
    void theSongsNotInTheLibrary_whenThereIsOne() throws IOException {
        libraries = aLibrary();
        try {
            String html = render(Locale.forLanguageTag("pl"), null, TWO_EVENINGS, wedding(), "wedding-library-pl");
            String gaps = html.substring(html.indexOf("id=\"libraryGaps\""), html.indexOf("</section>", html.indexOf("id=\"libraryGaps\"")));

            assertThat(gaps).contains(">Nie masz w bibliotece<", "według obecnej biblioteki", ">Zenek Martyniuk - Przekorny los<",
                    ">Bajm - Biała armia<", "class=\"library-similar\"").doesNotContain("Sanah - Szampan", "Nirvana");
            assertThat(gaps.indexOf("Zenek Martyniuk")).as("the most wanted first").isLessThan(gaps.indexOf("Varius Manx"));

            ResponseEntity<byte[]> csv = new DjSummaryController(new EveningSummaryService(repositoryOf(wedding()), libraries, kept),
                    sessionHelperOf(), messages, com.scan2play.service.Plans.off(), "https://www.scan2play.com.pl").csv(null, null, Locale.ENGLISH, null, new MockHttpSession());
            assertThat(new String(csv.getBody(), StandardCharsets.UTF_8).split("\r\n")[0])
                    .endsWith(";AI comment;Added by the DJ;From the kiosk;Waited (min);Artist;Title;Dedications;Why the AI rejected it;In your library");
            // "Kopiuj listę": the songs one a line, shown by summary.js
            assertThat(gaps).contains("id=\"copyLibrary\" hidden", "data-copy-select=\"libraryList\"", "data-copied=\"Skopiowano\"",
                    ">Kopiuj listę<");
        } finally {
            libraries = mock(com.scan2play.service.DjLibraryService.class);
        }
    }

    @Test
    void withoutALibrary_noSuchSection() throws IOException {
        assertThat(render(Locale.forLanguageTag("pl"), null, TWO_EVENINGS, wedding(), "wedding-pl")).doesNotContain("id=\"libraryGaps\"");
    }

    /**
     * A title or a hosts' link only for an evening with requests — asked of that evening alone, never of the party's whole list of
     * evenings (13 months of a club's rows read again at every "Zapisz"); one without is a 404.
     */
    @Test
    void aTitleOrALink_onlyForAnEveningWithRequests_askedOfThatEvening() {
        EveningSummaryService service = mock(EveningSummaryService.class);
        DjSummaryController controller = new DjSummaryController(service, sessionHelperOf(), messages,
                com.scan2play.service.Plans.off(), "https://www.scan2play.com.pl");
        java.time.LocalDate day = java.time.LocalDate.of(2026, 10, 3);
        when(service.hasRequests(PARTY, day)).thenReturn(true);

        assertThat(controller.saveTitle("2026-10-03", null, "Wesele", null, new MockHttpSession()))
                .isEqualTo("redirect:/dj/summary?evening=2026-10-03&view=hosts");
        org.mockito.Mockito.verify(service).saveTitle(PARTY, day, "Wesele");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> controller.share("2026-10-04", null, null, new MockHttpSession()))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class).hasMessageContaining("404");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> controller.stopSharing("not a day", null, null, new MockHttpSession()))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class).hasMessageContaining("404");
        org.mockito.Mockito.verify(service, org.mockito.Mockito.never()).evenings(any());
        org.mockito.Mockito.verify(service, org.mockito.Mockito.never()).share(any(), any());
    }

    private static SongRequestRepository repositoryOf(List<SongRequestEntity> requests) {
        SongRequestRepository repository = mock(SongRequestRepository.class);
        when(repository.findEvenings(eq(PARTY), anyInt())).thenReturn(List.<Object[]>of(new Object[]{"2026-10-03", 8L}));
        when(repository.findRequestedBetween(eq(PARTY), any(), any(), any())).thenReturn(requests);
        return repository;
    }

    private static DjSessionHelper sessionHelperOf() {
        DjSessionHelper sessionHelper = mock(DjSessionHelper.class);
        when(sessionHelper.require(any(), any(), any(), any())).thenReturn(PartySettingsEntity.builder().partyCode(PARTY).build());
        return sessionHelper;
    }
}
