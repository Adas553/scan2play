package com.scan2play.template;

import com.scan2play.entity.SongRequestEntity;
import com.scan2play.model.VibeType;
import com.scan2play.service.GuestQueueService.GuestQueue;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.web.csrf.DefaultCsrfToken;
import org.thymeleaf.context.WebContext;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import org.thymeleaf.web.servlet.JakartaServletWebApplication;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Renders the real guest page ({@code index.html}) and result page with the real message bundles: a song is asked for (the DJ sets
 * the mood), the requests sent lately are listed, and a request the AI read as a mood comes back with its text.
 */
class GuestPageRenderTest {

    private static final Locale PL = Locale.forLanguageTag("pl");

    private static SpringTemplateEngine engine;

    @BeforeAll
    static void setUpEngine() {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCharacterEncoding("UTF-8");

        ResourceBundleMessageSource messages = new ResourceBundleMessageSource();
        messages.setBasename("messages");
        messages.setDefaultEncoding("UTF-8");
        messages.setFallbackToSystemLocale(false);

        engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
        engine.setTemplateEngineMessageSource(messages);
    }

    private static GuestQueue queue(List<SongRequestEntity> recent, Set<Long> mine, String mySong, List<SongRequestEntity> mostWanted) {
        return new GuestQueue(recent, mine, mySong, mine.size(), mostWanted);
    }

    private static String render(Locale locale, Map<String, Object> flash) {
        MockServletContext servletContext = new MockServletContext();
        WebContext context = new WebContext(
                JakartaServletWebApplication.buildApplication(servletContext)
                        .buildExchange(new MockHttpServletRequest(servletContext), new MockHttpServletResponse()),
                locale);
        Map<String, Object> model = new HashMap<>(Map.of(
                "globalVibe", VibeType.ANY, "guestQueue", queue(List.of(), Set.of(), null, List.of()), "partyCode", "ABC12"));
        model.putAll(flash);
        context.setVariables(model);
        context.setVariable("_csrf", new DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", "token"));
        return engine.process("index", context);
    }

    /** The opening tag of the element with this id. */
    private static String tag(String html, String id) {
        int at = html.indexOf("id=\"" + id + "\"");
        assertThat(at).as(id).isPositive();
        return html.substring(html.lastIndexOf('<', at), html.indexOf('>', at) + 1);
    }

    @Test
    void theFormAsksForASong_inBothLanguages() {
        for (Locale locale : List.of(PL, Locale.ENGLISH)) {
            String html = render(locale, Map.of());

            assertThat(html).as(locale.toString()).doesNotContain("??");
            assertThat(html).contains("id=\"songInput\"", "id=\"songInputHelp\"")
                    .doesNotContain("id=\"modeMood\"", "id=\"modeSong\"", "name=\"requestMode\"", "name=\"style\"", "id=\"styleInput\"");
            assertThat(html).as("no YouTube on the page").doesNotContain("Powered by YouTube", "YouTube API Services");
            assertThat(html).as("our logo is the page's heading").containsPattern(
                    "<h1 class=\"display-4 fw-bold\"><span class=\"s2p-logo\"><img src=\"/images/logo.svg\"[^>]*><span>Scan<span class=\"s2p-logo-two\">2</span>Play</span></span></h1>");
            // no inline script (CSP, review 5.1): the page's script is a file
            DashboardPageRenderTest.assertNothingInline(html);
            assertThat(html).contains("<script src=\"/js/guest-party.js\">");
            assertThat(tag(html, "submitBtn")).contains("data-text-submitting=\"");
        }
        writePreview("index.html", render(PL, Map.of()));
    }

    /** For a look in a browser (the page links /css/app.css): target/guest-page/NAME. */
    private static void writePreview(String name, String html) {
        try {
            java.nio.file.Path out = java.nio.file.Path.of("target", "guest-page", name);
            java.nio.file.Files.createDirectories(out.getParent());
            java.nio.file.Files.writeString(out, html, java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /** The page the browser tests open (src/test/browser, page 'guest'): it runs the real guest scripts under the real CSP. */
    private static void writeForTheBrowserTests(String name, String html) {
        try {
            java.nio.file.Path out = java.nio.file.Path.of("target", "browser-harness", name);
            java.nio.file.Files.createDirectories(out.getParent());
            java.nio.file.Files.writeString(out, html, java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static SongRequestEntity song(long id, String name) {
        return SongRequestEntity.builder().id(id).songName(name).build();
    }

    /** No order to tell (the DJ plays from their own software): the requests sent lately, unnumbered, and the guest's own "waits". */
    @Test
    void theRequestsSentLately_areListed_andTheGuestsOwnWaitsForTheDj() {
        // with who plays and the DJ's three profiles: the browser tests load the page — the icons too — under the real CSP
        String html = render(PL, Map.of("guestQueue", queue(List.of(song(3, "Newest"), song(2, "Mine"), song(1, "Oldest")),
                Set.of(2L), "Mine", List.of()), "djName", "DJ Koko", "instagramUrl", "https://www.instagram.com/dj.koko/",
                "facebookUrl", "https://www.facebook.com/djkoko", "tiktokUrl", "https://www.tiktok.com/@dj_koko"));
        writePreview("index-with-queue.html", html);
        writeForTheBrowserTests("guest.html", html);

        assertThat(html).doesNotContain("??");
        assertThat(html).contains("id=\"guestQueueBox\"", "data-url=\"/p/ABC12/queue\"");
        assertThat(html).contains("Ostatnio wysłane", "Twoja prośba „Mine” czeka u DJ-a")
                .doesNotContain("Następne w kolejce", "w kolejce", "Teraz gra", "list-group-numbered");
        assertThat(html.indexOf("Newest")).as("the newest first").isLessThan(html.indexOf(">Mine<"));
        String mine = html.substring(html.indexOf(">Mine<"), html.indexOf("Oldest"));
        assertThat(mine).as("the guest's own song is marked").contains("Twoja");
        assertThat(html.substring(html.indexOf("Newest"), html.indexOf(">Mine<"))).doesNotContain("Twoja");
    }

    /** Several of the guest's songs wait: counted, none named — naming one read as the AI's mix-up (the owner, 2026-10-07). */
    @Test
    void severalOfTheGuestsSongsWaiting_areCounted_notNamed() {
        String html = render(PL, Map.of("guestQueue", queue(List.of(song(3, "Third"), song(2, "Second"), song(1, "First")),
                Set.of(1L, 2L, 3L), null, List.of())));

        assertThat(html).contains("Czekają u DJ-a Twoje prośby: 3").doesNotContain("Twoja prośba „", "??");
        assertThat(html.split(">Twoja<", -1)).as("each of them marked in the list").hasSize(4);
    }

    /** The result page is about this request alone: no other of the guest's waiting songs on it. */
    @Test
    void theResultPage_namesNoOtherWaitingSong() {
        String html = renderResult(new com.scan2play.model.DjResponse("accepted", "Dobry wybór", "Golec uOrkiestra - Ściernisko", 7, "title"));

        assertThat(html).contains("TAK!").doesNotContain("id=\"myPosition\"", "czeka u DJ-a", "Czekają u DJ-a");
    }

    /** GET /p/{code}/queue renders the fragment alone: the same list, with its refresh button, and nothing when it is empty. */
    @Test
    void theFragmentAlone_isTheList_withItsRefreshButton() {
        MockServletContext servletContext = new MockServletContext();
        WebContext context = new WebContext(
                JakartaServletWebApplication.buildApplication(servletContext)
                        .buildExchange(new MockHttpServletRequest(servletContext), new MockHttpServletResponse()), PL);
        context.setVariable("guestQueue", queue(List.of(song(1, "First")), Set.of(), null, List.of()));
        String fragment = engine.process("fragments/guest-queue", Set.of("guestQueue"), context);

        assertThat(fragment).contains("data-guest-queue-refresh", "Odśwież", "First").doesNotContain("??", "<html");

        context.setVariable("guestQueue", null);
        assertThat(engine.process("fragments/guest-queue", Set.of("guestQueue"), context).strip()).isEmpty();
    }

    @Test
    void anEmptyQueue_showsNoList() {
        String html = render(PL, Map.of());

        assertThat(html).doesNotContain("id=\"upNext\"", "id=\"myPosition\"");
    }

    @Test
    void aMood_comesBackWithItsText_askingForASong() {
        String html = render(PL, Map.of("lastRequest", "coś do tańca", "errorMessage", "Tutaj DJ przyjmuje konkretne piosenki"));

        assertThat(tag(html, "songInput")).contains("value=\"coś do tańca\"");
        assertThat(html).contains("Tutaj DJ przyjmuje konkretne piosenki");
    }

    private static String renderResult(com.scan2play.model.DjResponse response) {
        MockServletContext servletContext = new MockServletContext();
        WebContext context = new WebContext(JakartaServletWebApplication.buildApplication(servletContext)
                .buildExchange(new MockHttpServletRequest(servletContext), new MockHttpServletResponse()), PL);
        context.setVariables(Map.of("response", response, "partyCode", "ABC12"));
        return engine.process("result", context);
    }

    /** The AI could not be asked: the request went to the DJ — "sent", not "yes", and no energy. */
    @Test
    void aRequestPassedOnWithoutTheAi_saysItWentToTheDj() {
        String unchecked = renderResult(new com.scan2play.model.DjResponse("accepted", "AI jest chwilowo niedostępne", "sanah", 0,
                com.scan2play.model.DjResponse.KIND_UNCHECKED));
        assertThat(unchecked).contains("PRZEKAZANE", "AI jest chwilowo niedostępne").doesNotContain("TAK!", "Energia", "Energy");
        assertThat(unchecked).as("no YouTube API").doesNotContain("YouTube API Services");

        String accepted = renderResult(new com.scan2play.model.DjResponse("accepted", "Dobry wybór", "sanah - Szampan", 7, "title"));
        assertThat(accepted).contains("TAK!", "Energia: 7/10").doesNotContain("PRZEKAZANE", "Energy");
    }

    /** The songs more than one guest asked for, listed with their votes above the list; the list's songs show theirs too. */
    @Test
    void theMostWantedSongs_areListedWithTheirVotes() {
        SongRequestEntity wilki = SongRequestEntity.builder().id(1L).songName("Wilki - Baśka").votes(4).build();
        SongRequestEntity sanah = SongRequestEntity.builder().id(2L).songName("sanah - Szampan").votes(2).build();
        String html = render(PL, Map.of("guestQueue", queue(List.of(sanah, wilki, song(3, "Alone")), Set.of(), null, List.of(wilki, sanah))));

        assertThat(html).contains("id=\"mostWanted\"", "🔥 Najwięcej głosów", "👍 4", "👍 2").doesNotContain("??");
        String mostWanted = html.substring(html.indexOf("id=\"mostWanted\""), html.indexOf("id=\"upNext\""));
        assertThat(mostWanted.indexOf("Wilki - Baśka")).as("the most votes first").isLessThan(mostWanted.indexOf("sanah - Szampan"));
        assertThat(mostWanted).doesNotContain("Alone");
        assertThat(html.substring(html.indexOf("id=\"upNext\""))).as("one guest's song has no badge").contains("👍 4", "👍 2")
                .doesNotContain("👍 1");
    }

    @Test
    void noSongWithMoreThanOneVote_noMostWantedList() {
        String html = render(PL, Map.of("guestQueue", queue(List.of(song(1, "Alone")), Set.of(), null, List.of())));

        assertThat(html).doesNotContain("id=\"mostWanted\"", "Najwięcej głosów", "👍");
    }

    /** The same song already waited: the guest's request was one more vote on it — or it was their own, and nothing changed. */
    @Test
    void aVote_andTheGuestsOwnSongAskedForAgain_sayWhatHappened() {
        String vote = renderResult(new com.scan2play.model.DjResponse("accepted", "Klasyk!", "Wilki - Baśka", 7, "title", 5L, 3, false));
        assertThat(vote).contains("Ktoś już o to prosił — dodaliśmy Twój głos! Głosów: 3").doesNotContain("id=\"voteOwn\"");

        String own = renderResult(new com.scan2play.model.DjResponse("accepted", "Klasyk!", "Wilki - Baśka", 7, "title", 5L, 2, true));
        assertThat(own).contains("Twoja prośba o tę piosenkę już czeka w kolejce. Głosów: 2").doesNotContain("id=\"voteAdded\"");

        String first = renderResult(new com.scan2play.model.DjResponse("accepted", "Klasyk!", "Wilki - Baśka", 7, "title", 5L, 1, false));
        assertThat(first).doesNotContain("id=\"voteAdded\"", "id=\"voteOwn\"");
    }

    /** The DJ's vibe and note (V16) in one calm box above the button; the guests pick no vibe. */
    @Test
    void theDjsVibeAndNote_areShownInOneBox() {
        String withNote = render(PL, Map.of("vibeNote", "wesele 40+, <b>bez rapu</b>"));
        assertThat(withNote).contains("id=\"vibeNote\"", "🎧 Klimat imprezy", "wesele 40+, &lt;b&gt;bez rapu&lt;/b&gt;")
                .doesNotContain("??", "id=\"styleInput\"");
        assertThat(withNote).as("no vibe of the list: no name after the title, the colon still — the note follows it")
                .doesNotContain("id=\"partyVibeName\"").contains("🎧 Klimat imprezy</span>:");

        String both = render(PL, Map.of("vibeNote", "wesele +40", "globalVibe", VibeType.CLUB_AND_EDM));
        assertThat(both).containsOnlyOnce("id=\"partyVibe\"").contains("id=\"partyVibeName\">Klubowa &amp; EDM<", "wesele +40")
                .contains("🎧 Klimat imprezy</span>: <span")
                .doesNotContain("UWAGA", "id=\"styleInput\"", "name=\"style\"");
        assertThat(render(PL, Map.of())).as("any vibe, no note: no box").doesNotContain("id=\"partyVibe\"");
    }

    /** Who plays (V17): "🎧 Gra: DJ Koko" under the title, escaped; nothing when the DJ wrote nothing. */
    @Test
    void whoPlays_isShownUnderTheTitle() {
        assertThat(render(PL, Map.of("djName", "DJ <b>Koko</b>")))
                .contains("id=\"djName\"", "🎧 Gra: DJ &lt;b&gt;Koko&lt;/b&gt;").doesNotContain("??");
        assertThat(render(Locale.ENGLISH, Map.of("djName", "DJ Koko"))).contains("🎧 Playing: DJ Koko");
        assertThat(render(PL, Map.of())).doesNotContain("id=\"djName\"");
    }

    /** The empty field shows the suggestions' own format, "Wykonawca – Tytuł" (song-autocomplete.js fills it in so). */
    @Test
    void theSongField_showsTheSuggestionsFormat() {
        assertThat(render(PL, Map.of())).contains("placeholder=\"Wykonawca – Tytuł\"");
        assertThat(render(Locale.ENGLISH, Map.of())).contains("placeholder=\"Artist – Title\"");
    }

    /** The DJ's profiles (V24): a button for each one the DJ gave, opened in a new tab, nothing passed to the site. */
    @Test
    void theDjsProfiles_areButtons() {
        String html = render(PL, Map.of("instagramUrl", "https://www.instagram.com/dj.koko/", "tiktokUrl", "https://www.tiktok.com/@dj_koko"));

        assertThat(html).contains("id=\"djLinks\"",
                "<a href=\"https://www.instagram.com/dj.koko/\" target=\"_blank\" rel=\"noopener noreferrer nofollow\"",
                "href=\"https://www.tiktok.com/@dj_koko\"", ">Instagram<", ">TikTok<").doesNotContain(">Facebook<");
        assertThat(html.split("<svg ", -1)).as("an icon on each button, drawn in the text's colour").hasSize(3);
        assertThat(html).contains("fill=\"currentColor\" viewBox=\"0 0 16 16\" aria-hidden=\"true\"");
        assertThat(render(PL, Map.of())).doesNotContain("id=\"djLinks\"");
    }
}
