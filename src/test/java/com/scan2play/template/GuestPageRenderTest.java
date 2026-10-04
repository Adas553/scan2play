package com.scan2play.template;

import com.scan2play.entity.SongRequestEntity;
import com.scan2play.model.MusicProviderType;
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
 * Renders the real guest page ({@code index.html}) with the real message bundles and checks the two request modes: a specific
 * song (the default) or a mood, and the way back to the form after a mood was sent as a song — the text kept, the mood chosen.
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

    private static String render(Locale locale, Map<String, Object> flash) {
        MockServletContext servletContext = new MockServletContext();
        WebContext context = new WebContext(
                JakartaServletWebApplication.buildApplication(servletContext)
                        .buildExchange(new MockHttpServletRequest(servletContext), new MockHttpServletResponse()),
                locale);
        Map<String, Object> model = new HashMap<>(Map.of(
                "globalVibe", VibeType.ANY, "activeProvider", MusicProviderType.YOUTUBE,
                "guestQueue", new GuestQueue(null, List.of(), Set.of(), null, null), "partyCode", "ABC12"));
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
    void theFormOffersBothModes_theSongModeChosen_inBothLanguages() {
        for (Locale locale : List.of(PL, Locale.ENGLISH)) {
            String html = render(locale, Map.of());

            assertThat(html).as(locale.toString()).doesNotContain("??");
            assertThat(tag(html, "modeSong")).contains("name=\"requestMode\"", "value=\"SONG\"", "checked");
            assertThat(tag(html, "modeMood")).contains("value=\"MOOD\"").doesNotContain("checked");
            assertThat(tag(html, "songInputHelp")).contains("data-text-song=\"", "data-text-mood=\"");
            // no inline script (CSP, review 5.1): the page's script is a file, its text a data attribute
            DashboardPageRenderTest.assertNothingInline(html);
            assertThat(html).contains("<script src=\"/js/guest-party.js\">");
            assertThat(tag(html, "submitBtn")).contains("data-text-submitting=\"");
        }
        String pl = render(PL, Map.of());
        assertThat(pl).contains("Konkretna piosenka", "Nastrój", "Czego chcesz?");
        writePreview("index.html", pl);
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

    @Test
    void theQueue_saysWhatPlays_whatComesNextInOrder_andWhereTheGuestsSongWaits() {
        GuestQueue queue = new GuestQueue("Wilki - Baśka", List.of(song(1, "First"), song(2, "Mine"), song(3, "Third")),
                Set.of(2L), 2, "Mine");
        String html = render(PL, Map.of("guestQueue", queue));
        writePreview("index-with-queue.html", html);
        writeForTheBrowserTests("guest.html", html);

        assertThat(html).doesNotContain("??");
        assertThat(html).contains("id=\"guestQueueBox\"", "data-url=\"/p/ABC12/queue\"");
        assertThat(html).contains("Twoja piosenka „Mine” — 2. w kolejce", "Teraz gra", "Wilki - Baśka", "Następne w kolejce");
        assertThat(html.indexOf("First")).as("in the order they play").isLessThan(html.indexOf(">Mine<"));
        assertThat(html.indexOf(">Mine<")).isLessThan(html.indexOf("Third"));
        String mine = html.substring(html.indexOf(">Mine<"), html.indexOf("Third"));
        assertThat(mine).as("the guest's own song is marked").contains("Twoja");
        assertThat(html.substring(html.indexOf("First"), html.indexOf(">Mine<"))).doesNotContain("Twoja");
    }

    /** GET /p/{code}/queue renders the fragment alone: the same list, with its refresh button, and nothing when it is empty. */
    @Test
    void theFragmentAlone_isTheList_withItsRefreshButton() {
        MockServletContext servletContext = new MockServletContext();
        WebContext context = new WebContext(
                JakartaServletWebApplication.buildApplication(servletContext)
                        .buildExchange(new MockHttpServletRequest(servletContext), new MockHttpServletResponse()), PL);
        context.setVariable("guestQueue", new GuestQueue("Wilki - Baśka", List.of(song(1, "First")), Set.of(), null, null));
        String fragment = engine.process("fragments/guest-queue", Set.of("guestQueue"), context);

        assertThat(fragment).contains("data-guest-queue-refresh", "Odśwież", "Teraz gra", "Wilki - Baśka", "First")
                .doesNotContain("??", "<html");

        context.setVariable("guestQueue", null);
        assertThat(engine.process("fragments/guest-queue", Set.of("guestQueue"), context).strip()).isEmpty();
    }

    @Test
    void anEmptyQueue_showsNoList() {
        String html = render(PL, Map.of());

        assertThat(html).doesNotContain("id=\"upNext\"", "id=\"nowPlaying\"", "id=\"myPosition\"");
    }

    @Test
    void aMoodSentAsASong_comesBackWithItsText_andTheMoodModeChosen() {
        String html = render(PL, Map.of("lastRequest", "coś do tańca", "suggestedMode", "MOOD",
                "errorMessage", "To wygląda na opis nastroju"));

        assertThat(tag(html, "modeMood")).contains("checked");
        assertThat(tag(html, "modeSong")).doesNotContain("checked");
        assertThat(tag(html, "songInput")).contains("value=\"coś do tańca\"");
        assertThat(html).contains("To wygląda na opis nastroju");
    }

    private static String renderResult(com.scan2play.model.DjResponse response) {
        MockServletContext servletContext = new MockServletContext();
        WebContext context = new WebContext(JakartaServletWebApplication.buildApplication(servletContext)
                .buildExchange(new MockHttpServletRequest(servletContext), new MockHttpServletResponse()), PL);
        context.setVariables(Map.of("response", response, "partyCode", "ABC12", "activeProvider", MusicProviderType.REQUESTS_ONLY));
        return engine.process("result", context);
    }

    /** The AI could not be asked at a requests-only party: the request went to the DJ — "sent", not "yes", and no energy. */
    @Test
    void aRequestPassedOnWithoutTheAi_saysItWentToTheDj() {
        String unchecked = renderResult(new com.scan2play.model.DjResponse("accepted", "AI jest chwilowo niedostępne", "sanah", 0,
                com.scan2play.model.DjResponse.KIND_UNCHECKED));
        assertThat(unchecked).contains("PRZEKAZANE", "AI jest chwilowo niedostępne").doesNotContain("TAK!", "Energy:");
        assertThat(unchecked).as("a requests-only party uses no YouTube API").doesNotContain("YouTube API Services");

        String accepted = renderResult(new com.scan2play.model.DjResponse("accepted", "Dobry wybór", "sanah - Szampan", 7, "title"));
        assertThat(accepted).contains("TAK!", "Energy:").doesNotContain("PRZEKAZANE");
    }

    /** The songs more than one guest asked for, listed with their votes above the queue; the queue's songs show theirs too. */
    @Test
    void theMostWantedSongs_areListedWithTheirVotes() {
        SongRequestEntity wilki = SongRequestEntity.builder().id(1L).songName("Wilki - Baśka").votes(4).build();
        SongRequestEntity sanah = SongRequestEntity.builder().id(2L).songName("sanah - Szampan").votes(2).build();
        GuestQueue queue = new GuestQueue(null, List.of(sanah, wilki, song(3, "Alone")), Set.of(), null, null, List.of(wilki, sanah), true);
        String html = render(PL, Map.of("guestQueue", queue));

        assertThat(html).contains("id=\"mostWanted\"", "🔥 Najwięcej głosów", "👍 4", "👍 2").doesNotContain("??");
        String mostWanted = html.substring(html.indexOf("id=\"mostWanted\""), html.indexOf("id=\"upNext\""));
        assertThat(mostWanted.indexOf("Wilki - Baśka")).as("the most votes first").isLessThan(mostWanted.indexOf("sanah - Szampan"));
        assertThat(mostWanted).doesNotContain("Alone");
        assertThat(html.substring(html.indexOf("id=\"upNext\""))).as("one guest's song has no badge").contains("👍 4", "👍 2")
                .doesNotContain("👍 1");
    }

    /** A requests-only party: no order to tell — the requests sent lately, unnumbered, and the guest's own "waits for the DJ". */
    @Test
    void aRequestsOnlyParty_listsTheRequestsSentLately_andTheGuestsWaitsForTheDj() {
        GuestQueue queue = new GuestQueue(null, List.of(song(2, "Newest"), song(1, "Mine")), Set.of(1L), 2, "Mine", List.of(), false);
        String html = render(PL, Map.of("guestQueue", queue));

        assertThat(html).contains("Ostatnio wysłane", "Twoja prośba „Mine” czeka u DJ-a").doesNotContain("Następne w kolejce", "w kolejce", "??");
        assertThat(html).containsPattern("<ol class=\"list-group shadow-sm\" id=\"upNext\">");

        String inOrder = render(PL, Map.of("guestQueue", new GuestQueue(null, List.of(song(1, "Mine")), Set.of(1L), 1, "Mine")));
        assertThat(inOrder).contains("Następne w kolejce", "Twoja piosenka „Mine” — 1. w kolejce", "list-group-numbered");
    }

    @Test
    void noSongWithMoreThanOneVote_noMostWantedList() {
        String html = render(PL, Map.of("guestQueue", new GuestQueue(null, List.of(song(1, "Alone")), Set.of(), null, null)));

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

    /** The DJ's vibe note (V16) is shown above the form's vibe; a requests-only party's guests pick no vibe. */
    @Test
    void theDjsVibeNote_isShown_andARequestsOnlyPartysGuestsPickNoVibe() {
        String withNote = render(PL, Map.of("vibeNote", "wesele 40+, <b>bez rapu</b>"));
        assertThat(withNote).contains("id=\"vibeNote\"", "🎧 Klimat imprezy", "wesele 40+, &lt;b&gt;bez rapu&lt;/b&gt;", "id=\"styleInput\"")
                .doesNotContain("??");
        assertThat(withNote).as("the new vibes are offered").contains("Polskie przeboje", "Latino (salsa, bachata, reggaeton)", "Dla dzieci")
                .doesNotContain("Salsa & Timba");

        assertThat(withNote).as("no vibe of the list: no name after the title, the colon still — the note follows it")
                .doesNotContain("id=\"partyVibeName\"").contains("🎧 Klimat imprezy</span>:");

        // the vibe of the list and the note in ONE box — no second, alarming one
        String both = render(PL, Map.of("vibeNote", "wesele +40", "globalVibe", VibeType.CLUB_AND_EDM));
        assertThat(both).containsOnlyOnce("id=\"partyVibe\"").contains("id=\"partyVibeName\">Klubowa &amp; EDM<", "wesele +40")
                .contains("🎧 Klimat imprezy</span>: <span")
                .doesNotContain("UWAGA", "id=\"styleInput\"");
        assertThat(both).as("the forced vibe still goes with the form").contains("name=\"style\" value=\"Klubowa &amp; EDM\"");
        assertThat(render(PL, Map.of())).as("any vibe, no note: no box").doesNotContain("id=\"partyVibe\"");

        String requestsOnly = render(PL, Map.of("activeProvider", MusicProviderType.REQUESTS_ONLY));
        assertThat(requestsOnly).doesNotContain("id=\"styleInput\"", "id=\"vibeNote\"");
    }

    /** Who plays (V17): "🎧 Gra: DJ Koko" under the title, escaped; nothing when the DJ wrote nothing. */
    @Test
    void whoPlays_isShownUnderTheTitle() {
        assertThat(render(PL, Map.of("djName", "DJ <b>Koko</b>")))
                .contains("id=\"djName\"", "🎧 Gra: DJ &lt;b&gt;Koko&lt;/b&gt;").doesNotContain("??");
        assertThat(render(Locale.ENGLISH, Map.of("djName", "DJ Koko"))).contains("🎧 Playing: DJ Koko");
        assertThat(render(PL, Map.of())).doesNotContain("id=\"djName\"");
    }

    /** A requests-only party takes specific songs only: no song / mood tiles, the song mode sent as a hidden field. */
    @Test
    void aRequestsOnlyParty_asksForASong_withoutTheMoodTiles() {
        String html = render(PL, Map.of("activeProvider", MusicProviderType.REQUESTS_ONLY,
                "guestQueue", new GuestQueue(null, List.of(song(1, "Wilki - Baśka")), Set.of(), null, null)));

        assertThat(html).doesNotContain("id=\"modeMood\"", "id=\"modeSong\"", "Nastrój", "Powered by YouTube", "YouTube API Services");
        assertThat(html).contains("type=\"hidden\" name=\"requestMode\" value=\"SONG\"", "id=\"songInput\"", "id=\"songInputHelp\"");
        DashboardPageRenderTest.assertNothingInline(html);
        writeForTheBrowserTests("guest-requests.html", html);

        assertThat(render(PL, Map.of())).as("a YouTube party keeps the tiles").contains("id=\"modeMood\"");
    }
}
