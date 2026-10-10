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

    /** A list of five or fewer (nothing folded), no 👍 given. */
    private static GuestQueue queue(List<SongRequestEntity> shown, Set<Long> mine, String mySong) {
        return new GuestQueue(shown, List.of(), mine, mySong, mine.size(), Set.of());
    }

    private static String render(Locale locale, Map<String, Object> flash) {
        MockServletContext servletContext = new MockServletContext();
        WebContext context = new WebContext(
                JakartaServletWebApplication.buildApplication(servletContext)
                        .buildExchange(new MockHttpServletRequest(servletContext), new MockHttpServletResponse()),
                locale);
        Map<String, Object> model = new HashMap<>(Map.of(
                "globalVibe", VibeType.ANY, "guestQueue", queue(List.of(), Set.of(), null), "partyCode", "ABC12"));
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
                    "<h1 class=\"display-5 fw-bold mb-2\"><span class=\"s2p-logo\"><img src=\"/images/logo.svg\"[^>]*><span>Scan<span class=\"s2p-logo-two\">2</span>Play</span></span></h1>");
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

    /** A song with its number at the party (V28): "#18" before it on the list. */
    private static SongRequestEntity numbered(long id, String name, int number) {
        return SongRequestEntity.builder().id(id).songName(name).requestNumber(number).build();
    }

    /** No order to tell (the DJ plays from their own software): the guests' requests, unnumbered, and the guest's own "waits". */
    @Test
    void theGuestsRequests_areListed_andTheGuestsOwnWaitsForTheDj() {
        // with who plays and the DJ's three profiles: the browser tests load the page — the icons too — under the real CSP
        String html = render(PL, Map.of("guestQueue", queue(List.of(numbered(3, "Newest", 18), numbered(2, "Mine", 17), numbered(1, "Oldest", 16)),
                Set.of(2L), "Mine"), "djName", "DJ Koko", "instagramUrl", "https://www.instagram.com/dj.koko/",
                "facebookUrl", "https://www.facebook.com/djkoko", "tiktokUrl", "https://www.tiktok.com/@dj_koko"));
        writePreview("index-with-queue.html", html);
        writeForTheBrowserTests("guest.html", html);

        assertThat(html).doesNotContain("??");
        assertThat(html).contains("id=\"guestQueueBox\"", "data-url=\"/p/ABC12/queue\"");
        assertThat(html).contains(">Prośby gości<", "Twoja prośba „Mine” czeka u DJ-a")
                .doesNotContain("Następne w kolejce", "w kolejce", "Teraz gra", "list-group-numbered", "Ostatnio wysłane", "Najwięcej głosów");
        assertThat(html.indexOf("Newest")).as("the order given (the service's)").isLessThan(html.indexOf(">Mine<"));
        String mine = html.substring(html.indexOf(">Mine<"), html.indexOf("Oldest"));
        assertThat(mine).as("the guest's own song is marked").contains("Twoja");
        assertThat(html.substring(html.indexOf("Newest"), html.indexOf(">Mine<"))).doesNotContain("Twoja");
    }

    /** Several of the guest's songs wait: counted, none named — naming one read as the AI's mix-up (the owner, 2026-10-07). */
    @Test
    void severalOfTheGuestsSongsWaiting_areCounted_notNamed() {
        String html = render(PL, Map.of("guestQueue", queue(List.of(song(3, "Third"), song(2, "Second"), song(1, "First")),
                Set.of(1L, 2L, 3L), null)));

        assertThat(html).contains("Czekają u DJ-a Twoje prośby: 3").doesNotContain("Twoja prośba „", "??");
        assertThat(html.split(">Twoja<", -1)).as("each of them marked in the list").hasSize(4);
    }

    /** The result page is about this request alone: no other of the guest's waiting songs on it. */
    @Test
    void theResultPage_namesNoOtherWaitingSong() {
        String html = renderResult(new com.scan2play.model.DjResponse("accepted", "Dobry wybór", "Golec uOrkiestra - Ściernisko", "title"));

        assertThat(html).contains("TAK!").doesNotContain("id=\"myPosition\"", "czeka u DJ-a", "Czekają u DJ-a");
    }

    /** GET /p/{code}/queue renders the fragment alone: the same list, with its refresh button, and nothing when it is empty. */
    @Test
    void theFragmentAlone_isTheList_withItsRefreshButton() {
        MockServletContext servletContext = new MockServletContext();
        WebContext context = new WebContext(
                JakartaServletWebApplication.buildApplication(servletContext)
                        .buildExchange(new MockHttpServletRequest(servletContext), new MockHttpServletResponse()), PL);
        context.setVariable("guestQueue", queue(List.of(song(1, "First")), Set.of(), null));
        String fragment = engine.process("fragments/guest-queue", Set.of("guestQueue"), context);

        assertThat(fragment).contains("data-guest-queue-refresh", "Odśwież", "First").doesNotContain("??", "<html");

        context.setVariable("guestQueue", null);
        assertThat(engine.process("fragments/guest-queue", Set.of("guestQueue"), context).strip()).isEmpty();
    }

    /** The hosts' page (V29, /h/{token}): both lists, the form to its own secret address, no Referer and no search engine. */
    @Test
    void theHostsPage_showsBothLists_andPostsToItsLink() {
        for (Locale locale : List.of(PL, Locale.ENGLISH)) {
            MockServletContext servletContext = new MockServletContext();
            WebContext context = new WebContext(
                    JakartaServletWebApplication.buildApplication(servletContext)
                            .buildExchange(new MockHttpServletRequest(servletContext), new MockHttpServletResponse()), locale);
            context.setVariables(Map.of("hostToken", "AbC_123-xyz", "djName", "DJ Koko", "hostBlocked", "Akcent\nBaby Shark",
                    "hostWanted", "Hej sokoły", "hostSaved", true));
            context.setVariable("_csrf", new DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", "token"));
            String html = engine.process("host", context);
            if (locale == PL) {
                writePreview("host.html", html);
                assertThat(html).contains("🚫 Nie grać", "⭐ Koniecznie zagrać", "✓ Zapisane", "🎧 Gra: DJ Koko");
            }

            assertThat(html).doesNotContain("??");
            assertThat(html).contains("action=\"/h/AbC_123-xyz\"", "method=\"post\"", "content=\"no-referrer\"", "noindex");
            assertThat(html).contains(">Akcent\nBaby Shark</textarea>", ">Hej sokoły</textarea>");
        }
    }

    /** The staff's invitation (V30, /join/{token}): the party named, one button through the login, no Referer, no search engine. */
    @Test
    void theInvitationPage_namesTheParty_andLeadsToTheLogin() {
        for (Locale locale : List.of(PL, Locale.ENGLISH)) {
            MockServletContext servletContext = new MockServletContext();
            WebContext context = new WebContext(
                    JakartaServletWebApplication.buildApplication(servletContext)
                            .buildExchange(new MockHttpServletRequest(servletContext), new MockHttpServletResponse()), locale);
            context.setVariables(Map.of("joinPartyName", "Klub Ola", "joinOwnerName", "Ola Kowalska", "joinLoggedIn", false,
                    "joinToken", "invite", "joinRole", com.scan2play.model.StaffRole.QUEUE,
                    "joinPermissions", List.copyOf(com.scan2play.model.StaffRole.QUEUE.permissions())));
            String html = engine.process("join", context);
            if (locale == PL) {
                writePreview("join.html", html);
                assertThat(html).contains(">Ola Kowalska zaprasza Cię do obsługi imprezy: Klub Ola<",
                        "Zaczynasz z rolą „Obsługa kolejki”. Możesz:", ">Zagrane, Pomiń, Cofnij, Przywróć<", ">Historia<",
                        ">Zaloguj się przez Google i dołącz<");
            }

            assertThat(html).doesNotContain("??", "action=\"/join/invite\"");
            assertThat(html).contains("id=\"joinButton\"", "href=\"/start\"", "content=\"no-referrer\"", "noindex");
        }
    }

    /**
     * Logged in, the invitation asks: "Dołącz" is a form (POST, its CSRF token), "Nie, dziękuję" leads away — opening the page joins
     * nothing (the review, 2026-10-10). A full staff or an old link says what happened.
     */
    @Test
    void theInvitationPage_loggedIn_asks_andAProblemIsSaid() {
        MockServletContext servletContext = new MockServletContext();
        WebContext context = new WebContext(JakartaServletWebApplication.buildApplication(servletContext)
                .buildExchange(new MockHttpServletRequest(servletContext), new MockHttpServletResponse()), PL);
        context.setVariables(Map.of("joinPartyName", "Klub Ola", "joinLoggedIn", true, "joinToken", "invite",
                "joinRole", com.scan2play.model.StaffRole.QUEUE, "joinPermissions", List.copyOf(com.scan2play.model.StaffRole.QUEUE.permissions())));
        String html = engine.process("join", context);
        writePreview("join-confirm.html", html);
        assertThat(html).contains(">Zaproszenie do obsługi: Klub Ola<", "action=\"/join/invite\" method=\"post\"", ">Dołącz<",
                        "id=\"joinDecline\"", ">Nie, dziękuję<")
                .doesNotContain("href=\"/start\"", "zaprasza Cię", "/dj/invitation", "??");

        // a link made with "Własne" (V33): its ticked permissions — none ticked: only what everyone on the staff has
        context.setVariables(Map.of("joinPartyName", "Klub Ola", "joinLoggedIn", true, "joinToken", "invite",
                "joinRole", com.scan2play.model.StaffRole.CUSTOM, "joinPermissions",
                List.of(com.scan2play.model.StaffPermission.TIPS, com.scan2play.model.StaffPermission.SUMMARY)));
        String custom = engine.process("join", context);
        assertThat(custom).contains("Zaczynasz z rolą „Własne”. Możesz:", ">Liczenie napiwków 💸<", ">Podsumowanie wieczoru<")
                .doesNotContain(">Historia<", "??");

        context.setVariables(Map.of("joinPartyName", "Klub Ola", "joinProblem", "join.problem.full"));
        String full = engine.process("join", context);
        assertThat(full).contains(">Nie można dołączyć<", "Obsługa jest pełna (10 osób): Klub Ola.")
                .doesNotContain("id=\"joinButton\"", "??");
    }

    /**
     * An invitation by e-mail (V34, /dj/invitation): who invites, to which party, with which role; "Dołącz" and "Nie, dziękuję" are
     * both forms that name the invitation (POST, CSRF) — declining it takes it off the organiser's list.
     */
    @Test
    void anInvitationByEmail_asks_andBothAnswersArePosts() {
        MockServletContext servletContext = new MockServletContext();
        WebContext context = new WebContext(JakartaServletWebApplication.buildApplication(servletContext)
                .buildExchange(new MockHttpServletRequest(servletContext), new MockHttpServletResponse()), PL);
        context.setVariables(Map.of("joinPartyName", "Klub Ola", "joinOwnerName", "Ola Kowalska", "joinLoggedIn", true,
                "joinInvitationId", 3L, "joinRole", com.scan2play.model.StaffRole.VIEWER,
                "joinPermissions", List.of(com.scan2play.model.StaffPermission.HISTORY)));
        context.setVariable("_csrf", new DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", "token"));
        String html = engine.process("join", context);
        writePreview("join-invitation.html", html);

        assertThat(html).contains(">Ola Kowalska zaprasza Cię do obsługi imprezy: Klub Ola<", "Zaczynasz z rolą „Podgląd”. Możesz:",
                "action=\"/dj/invitation/accept\" method=\"post\"", "action=\"/dj/invitation/decline\" method=\"post\"",
                "name=\"id\" value=\"3\"", ">Dołącz<", ">Nie, dziękuję<")
                .doesNotContain("action=\"/join/", "href=\"/dj/dashboard\"", "href=\"/start\"", "??");
    }

    /** "👥 Jestem z obsługi" on the landing page (V30): folded, a form to POST /join; open with a note after a link that did not work. */
    @Test
    void theLandingPage_letsTheStaffPasteTheirLink() {
        String[] pages = new String[2];
        for (int i = 0; i < 2; i++) {
            MockServletContext servletContext = new MockServletContext();
            MockHttpServletRequest request = new MockHttpServletRequest(servletContext);
            if (i == 1) {
                request.setParameter("staffLink", "invalid");
            }
            WebContext context = new WebContext(JakartaServletWebApplication.buildApplication(servletContext)
                    .buildExchange(request, new MockHttpServletResponse()), PL);
            context.setVariable("_csrf", new DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", "token"));
            pages[i] = engine.process("landing", context);
        }
        writePreview("landing.html", pages[0]);

        assertThat(pages[0]).doesNotContain("??");
        assertThat(pages[0]).contains("id=\"staffLogin\"", "Masz zaproszenie do obsługi imprezy? Dołącz →", "action=\"/join\"",
                "name=\"link\"", ">Zaloguj się przez Google i dołącz<").doesNotContain("id=\"staffLinkInvalid\"");
        assertThat(tag(pages[0], "staffLogin")).as("folded at first").doesNotContain("open");
        assertThat(tag(pages[1], "staffLogin")).as("open after a link that did not work").contains("open");
        assertThat(pages[1]).contains("id=\"staffLinkInvalid\"", "Ten link nie działa — poproś organizatora o nowy.");
    }

    @Test
    void anEmptyQueue_showsNoList() {
        String html = render(PL, Map.of());

        assertThat(html).doesNotContain("id=\"guestRequests\"", "id=\"myPosition\"");
    }

    @Test
    void aMood_comesBackWithItsText_askingForASong() {
        String html = render(PL, Map.of("lastRequest", "coś do tańca", "errorMessage", "Tutaj DJ przyjmuje konkretne piosenki"));

        assertThat(tag(html, "songInput")).contains("value=\"coś do tańca\"");
        assertThat(html).contains("Tutaj DJ przyjmuje konkretne piosenki");
    }

    private static String renderResult(com.scan2play.model.DjResponse response) {
        return renderResult(response, null);
    }

    private static String renderResult(com.scan2play.model.DjResponse response, String tipUrl) {
        MockServletContext servletContext = new MockServletContext();
        WebContext context = new WebContext(JakartaServletWebApplication.buildApplication(servletContext)
                .buildExchange(new MockHttpServletRequest(servletContext), new MockHttpServletResponse()), PL);
        Map<String, Object> model = new HashMap<>(Map.of("response", response, "partyCode", "ABC12"));
        model.put("tipUrl", tipUrl);
        context.setVariables(model);
        return engine.process("result", context);
    }

    /**
     * The DJ's tip link (V27): a button on the party page, opened in a new tab, the link read under it with "straight to the DJ";
     * under a request only when it reached the DJ; none without a link.
     */
    @Test
    void theDjsTipLink_isAButton_onThePartyPage_andUnderAnAcceptedRequest() {
        String html = render(PL, Map.of("tipUrl", "https://revolut.me/djkoko"));

        assertThat(html).contains("id=\"djTip\"", "<a href=\"https://revolut.me/djkoko\" target=\"_blank\" rel=\"noopener noreferrer nofollow\"",
                "💸 Napiwek dla DJ-a", "revolut.me/djkoko · prosto do DJ-a, poza Scan2Play",
                "Jeśli chcesz, wpisz w tytule wpłaty numer piosenki z listy (np. #27)");
        assertThat(render(PL, Map.of())).doesNotContain("djTip", "Napiwek");

        String accepted = renderResult(new com.scan2play.model.DjResponse("accepted", "Dobry wybór", "sanah - Szampan", "title",
                5L, 1, false, 27), "https://www.paypal.com/paypalme/djkoko");
        // the link with "prosto do DJ-a" only on the party page: under a request one line fewer
        assertThat(accepted).doesNotContain("prosto do DJ-a", "paypal.com/paypalme/djkoko ·");
        assertThat(accepted).contains("href=\"https://www.paypal.com/paypalme/djkoko\"",
                "id=\"requestNumber\"", "Numer Twojej piosenki: #27", "Jeśli chcesz, wpisz #27 w tytule wpłaty — DJ będzie wiedział, za którą piosenkę");
        String rejected = renderResult(new com.scan2play.model.DjResponse("rejected", "Nie dziś", "Nirvana - Lithium", "title"),
                "https://revolut.me/djkoko");
        assertThat(rejected).doesNotContain("djTip", "revolut.me");
        assertThat(renderResult(new com.scan2play.model.DjResponse("accepted", "Dobry wybór", "sanah - Szampan", "title")))
                .doesNotContain("djTip");
    }

    /** The AI could not be asked: the request went to the DJ — "sent", not "yes", and no energy. */
    @Test
    void aRequestPassedOnWithoutTheAi_saysItWentToTheDj() {
        String unchecked = renderResult(new com.scan2play.model.DjResponse("accepted", "AI jest chwilowo niedostępne", "sanah",
                com.scan2play.model.DjResponse.KIND_UNCHECKED));
        assertThat(unchecked).contains("PRZEKAZANE", "AI jest chwilowo niedostępne").doesNotContain("TAK!", "Energia", "Energy");
        assertThat(unchecked).as("no YouTube API").doesNotContain("YouTube API Services");

        String accepted = renderResult(new com.scan2play.model.DjResponse("accepted", "Dobry wybór", "sanah - Szampan", "title"));
        // no energy rating anywhere (the owner, 2026-10-10)
        assertThat(accepted).contains("TAK!").doesNotContain("PRZEKAZANE", "Energia", "Energy");
    }

    /**
     * One list (the owner, 2026-10-08: three lists showed one song up to three times): each song once, in the service's order, with
     * its 👍 and count — one guest's too; no "Najwięcej głosów", no "Ostatnio wysłane".
     */
    @Test
    void oneList_eachSongOnce_withItsVotes() {
        SongRequestEntity wilki = SongRequestEntity.builder().id(1L).songName("Wilki - Baśka").votes(4).requestNumber(12).build();
        SongRequestEntity sanah = SongRequestEntity.builder().id(2L).songName("sanah - Szampan").votes(2).build();
        String html = render(PL, Map.of("guestQueue", queue(List.of(wilki, sanah, song(3, "Alone")), Set.of(), null)));

        assertThat(html).as("the songs' numbers (V28), for the title of a tip").contains(">#12<").doesNotContain(">#null<");
        assertThat(html).contains("id=\"guestRequests\"", ">Prośby gości<", ">👍 4<", ">👍 2<", ">👍 1<")
                .doesNotContain("??", "id=\"mostWanted\"", "id=\"upNext\"", "id=\"moreRequests\"", "Najwięcej głosów", "Ostatnio wysłane");
        assertThat(html.split(">Wilki - Baśka<", -1)).as("once").hasSize(2);
        assertThat(html.indexOf("Wilki - Baśka")).isLessThan(html.indexOf("sanah - Szampan"));
        assertThat(html).contains("data-song-id=\"1\"", "data-song-id=\"2\"", "data-song-id=\"3\"");
    }

    /**
     * The 👍 (the owner, 2026-10-08: one vote per song): every song but the guest's own has a button with its count — outlined to
     * give the vote, filled when given (a second tap takes it back); the guest's own shows "Twoja" and a green pill, no button.
     */
    @Test
    void everySongButTheGuestsOwn_hasAVoteButton_filledWhenGiven() {
        SongRequestEntity mine = SongRequestEntity.builder().id(1L).songName("Mine").votes(1).build();
        SongRequestEntity voted = SongRequestEntity.builder().id(2L).songName("Voted").votes(3).build();
        SongRequestEntity other = SongRequestEntity.builder().id(3L).songName("Other").votes(1).build();
        GuestQueue queue = new GuestQueue(List.of(voted, other, mine), List.of(), Set.of(1L), "Mine", 1, Set.of(2L));
        String html = render(PL, Map.of("guestQueue", queue));
        java.util.function.Function<String, String> row = name -> {
            String from = html.substring(html.indexOf(">" + name + "<"));
            return from.substring(0, from.indexOf("</li>"));
        };

        // one pill of one size on every row (the owner: badges of different sizes looked untidy); "Twoja" beside the name
        assertThat(row.apply("Mine")).contains(">Twoja<", "s2p-vote-pill s2p-vote-mine", "btn-success", ">👍 1<",
                "title=\"Twoja prośba — to już Twój głos\"").doesNotContain("<form", "s2p-vote-btn");
        assertThat(row.apply("Mine").indexOf(">Twoja<")).as("beside the name, before the pill").isLessThan(row.apply("Mine").indexOf("s2p-vote-pill"));
        assertThat(List.of(row.apply("Mine"), row.apply("Other"), row.apply("Voted"))).allSatisfy(r -> assertThat(r.split("s2p-vote-pill", -1)).hasSize(2));
        assertThat(row.apply("Other")).contains("action=\"/p/ABC12/vote\"", "name=\"id\" value=\"3\"", "name=\"on\" value=\"true\"",
                "btn-outline-warning", "aria-pressed=\"false\"", "title=\"Zagłosuj na tę piosenkę\"", ">👍 1<");
        assertThat(row.apply("Voted")).contains("name=\"on\" value=\"false\"", "btn-warning", "aria-pressed=\"true\"",
                "title=\"Twój głos — dotknij, aby go cofnąć\"", ">👍 3<").doesNotContain("btn-outline-warning");
        assertThat(html).contains("👍 Oddaj głos na piosenkę — DJ widzi, czego chcecie najbardziej").doesNotContain("??", "id=\"moreRequests\"");
    }

    /** A fragment of guest-queue.html alone, as the server renders it for the page's script. */
    private static String fragment(String name, Map<String, Object> variables) {
        MockServletContext servletContext = new MockServletContext();
        WebContext context = new WebContext(JakartaServletWebApplication.buildApplication(servletContext)
                .buildExchange(new MockHttpServletRequest(servletContext), new MockHttpServletResponse()), PL);
        context.setVariables(variables);
        context.setVariable("partyCode", "ABC12");
        return engine.process("fragments/guest-queue", Set.of(name), context);
    }

    /**
     * More than five waiting: the rest folded under "Pokaż pozostałe prośby (N)", NOT in the page — fetched when unfolded (the
     * fragment moreList: with 300 waiting, every guest's refresh carrying them all is too much) —, with a search over the list.
     */
    @Test
    void moreThanFiveWaiting_theRestIsFolded_fetchedWhenUnfolded_withASearch() {
        SongRequestEntity top = SongRequestEntity.builder().id(9L).songName("Top song").votes(5).build();
        List<SongRequestEntity> shown = new java.util.ArrayList<>(List.of(top));
        shown.addAll(java.util.stream.LongStream.rangeClosed(1, 4).mapToObj(i -> song(i, "Recent " + i)).toList());
        List<SongRequestEntity> more = List.of(song(5, "Older 5"), song(6, "Wilki - Baśka"));
        GuestQueue queue = new GuestQueue(shown, more, Set.of(), null, 0, Set.of());
        String html = render(PL, Map.of("guestQueue", queue));
        writeForTheBrowserTests("guest-many.html", html);

        assertThat(html).contains("<details", "id=\"moreRequests\"", "data-url=\"/p/ABC12/queue/more\"", "Pokaż pozostałe prośby (2)",
                "id=\"requestSearch\"", "placeholder=\"Szukaj w prośbach\"", "Nie ma takiej prośby").doesNotContain("??");
        assertThat(html).as("the rest is not in the page").doesNotContain("Older 5", "Wilki - Baśka");

        String rest = fragment("moreList", Map.of("guestQueue", queue));
        assertThat(rest).contains("Older 5", "Wilki - Baśka", "data-song-id=\"6\"").doesNotContain("Top song", "Recent 1", "<html", "??");
        assertThat(rest.split("s2p-vote-btn", -1)).as("a 👍 for each of the two").hasSize(3);
        writeForTheBrowserTests("guest-queue-more.html", rest);
    }

    /**
     * The answer to a 👍 sent in the background (the fragment voteAnswer): that song's row with its new votes, nothing else of the
     * list — the page takes only the votes, nothing moves —; when the song no longer waits, only the note.
     */
    @Test
    void theAnswerToAVote_isThatSongsRow_orTheNote() {
        SongRequestEntity voted = SongRequestEntity.builder().id(4L).songName("Recent 4").votes(2).build();
        GuestQueue queue = new GuestQueue(List.of(song(9, "Top song"), voted), List.of(), Set.of(), null, 0, Set.of(4L));
        String answer = fragment("voteAnswer", Map.of("guestQueue", queue, "voteSong", voted));
        assertThat(answer).contains("data-song-id=\"4\"", "aria-pressed=\"true\"", ">👍 2<")
                .doesNotContain("Top song", "id=\"voteNote\"", "<html", "??");
        writeForTheBrowserTests("guest-vote-answer.html", answer);

        String gone = fragment("voteAnswer", Map.of("guestQueue", queue,
                "voteNote", "Tej piosenki nie ma już w kolejce — DJ ją zagrał albo pominął."));
        assertThat(gone).contains("id=\"voteNote\"", "s2p-vote-note", "nie ma już w kolejce").doesNotContain("data-song-id");
        writeForTheBrowserTests("guest-vote-gone.html", gone);
    }

    /** The same song already waited: the guest's request was one more vote on it — or it was their own, and nothing changed. */
    @Test
    void aVote_andTheGuestsOwnSongAskedForAgain_sayWhatHappened() {
        String vote = renderResult(new com.scan2play.model.DjResponse("accepted", "Klasyk!", "Wilki - Baśka", "title", 5L, 3, false));
        assertThat(vote).contains("Ktoś już o to prosił — dodaliśmy Twój głos! Głosów:\u00a03").doesNotContain("id=\"voteOwn\"");

        String own = renderResult(new com.scan2play.model.DjResponse("accepted", "Klasyk!", "Wilki - Baśka", "title", 5L, 2, true));
        assertThat(own).contains("Ta piosenka już czeka w kolejce i ma Twój głos. Głosów:\u00a02").doesNotContain("id=\"voteAdded\"");

        String first = renderResult(new com.scan2play.model.DjResponse("accepted", "Klasyk!", "Wilki - Baśka", "title", 5L, 1, false));
        assertThat(first).doesNotContain("id=\"voteAdded\"", "id=\"voteOwn\"");
    }

    /** The DJ's vibe and note (V16) in one quiet line under who plays (the design review, 2026-10-09: a pale box between the field and the button); the guests pick no vibe. */
    @Test
    void theDjsVibeAndNote_areShownInOneBox() {
        String withNote = render(PL, Map.of("vibeNote", "wesele 40+, <b>bez rapu</b>"));
        assertThat(withNote).contains("id=\"vibeNote\"", ">Klimat imprezy<", "wesele 40+, &lt;b&gt;bez rapu&lt;/b&gt;")
                .doesNotContain("??", "id=\"styleInput\"");
        assertThat(withNote).as("no vibe of the list: no name after the title, the colon still — the note follows it")
                .doesNotContain("id=\"partyVibeName\"").contains("Klimat imprezy</span><span class=\"text-secondary\">:</span> <span id=\"vibeNote\"");

        String both = render(PL, Map.of("vibeNote", "wesele +40", "globalVibe", VibeType.CLUB_AND_EDM));
        assertThat(both).containsOnlyOnce("id=\"partyVibe\"").contains("id=\"partyVibeName\" class=\"fw-semibold\">Klubowa &amp; EDM<", "wesele +40")
                .contains("Klimat imprezy</span><span class=\"text-secondary\">:</span> <span id=\"partyVibeName\"")
                .doesNotContain("UWAGA", "id=\"styleInput\"", "name=\"style\"");
        assertThat(render(PL, Map.of())).as("any vibe, no note: no box").doesNotContain("id=\"partyVibe\"");
    }

    /**
     * The installed app takes every address of the site: a DJ testing their QR code landed on the guest page in it with no way back
     * (the owner, 2026-10-08). The guest page and the result page have "← Twój panel DJ-a", shown only in the app (app.css).
     */
    @Test
    void theGuestPages_haveTheWayBackToTheDashboard_forTheApp() {
        String page = render(PL, Map.of());
        String result = renderResult(new com.scan2play.model.DjResponse("accepted", "Dobry wybór", "sanah - Szampan", "title"));

        for (String html : List.of(page, result)) {
            assertThat(html).contains("s2p-standalone-only", "id=\"backToDashboard\"", "href=\"/dj/dashboard\"", "← Twój panel DJ-a")
                    .doesNotContain("??");
        }
        assertThat(render(Locale.ENGLISH, Map.of())).contains("← Your DJ dashboard");
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
