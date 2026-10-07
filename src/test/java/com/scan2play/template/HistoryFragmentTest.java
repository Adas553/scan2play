package com.scan2play.template;

import com.scan2play.model.HistoryEntry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Renders the real history fragment ({@code history.html :: historyTableContent}) with the real message bundles — the
 * DJ pages are behind a login, so this is what catches a broken expression or a missing message key.
 */
class HistoryFragmentTest {

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

    private static HistoryEntry guest(int i, String songName, String decision) {
        return new HistoryEntry((long) i, java.time.LocalDateTime.of(2026, 9, 29, 20, i % 60).atZone(com.scan2play.util.Times.DISPLAY_ZONE).toInstant(), songName,
                "https://www.youtube.com/results?search_query=" + songName.replace(' ', '+'), "Pop", decision, "ok", 7, null);
    }

    private static String render(List<HistoryEntry> history, boolean hasMore, Locale locale) {
        return render(history, hasMore, false, locale);
    }

    private static String render(List<HistoryEntry> history, boolean hasMore, boolean heading, Locale locale) {
        Context context = new Context(locale);
        context.setVariable("history", history);
        context.setVariable("historyHasMore", hasMore);
        context.setVariable("historyNextLimit", 100);
        if (heading) {
            context.setVariable("historyHeading", true);
        }
        return engine.process("history", Set.of("historyTableContent"), context);
    }

    @Test
    @DisplayName("in the dashboard's History tab the list has a heading of its own, so it is clear what has appeared")
    void shouldHaveAHeadingInTheTab() {
        String html = render(List.of(guest(1, "Alpha", "played")), false, true, Locale.ENGLISH);

        assertThat(html).contains("<h4", "Party History</h4>");
        assertThat(render(List.of(guest(1, "Alpha", "played")), false, true, PL)).contains("Historia imprezy</h4>");
    }

    @Test
    @DisplayName("on the standalone page there is no second heading: the page has its own h1")
    void shouldHaveNoHeadingOnTheStandalonePage() {
        String html = render(List.of(guest(1, "Alpha", "played")), false, Locale.ENGLISH);

        assertThat(html).doesNotContain("<h4");
    }

    @Test
    @DisplayName("every row carries its title and decision, for the search box and the Played / Rejected filter")
    void shouldMarkEachRowForTheFilters() {
        String html = render(List.of(guest(1, "Alpha", "played"), guest(2, "Beta", "rejected")), false, Locale.ENGLISH);

        assertThat(html).containsPattern("<tr[^>]*data-song-name=\"Alpha\"[^>]*data-decision=\"played\"");
        assertThat(html).containsPattern("<tr[^>]*data-song-name=\"Beta\"[^>]*data-decision=\"rejected\"");
    }

    @Test
    @DisplayName("the button of the filter in the model (historyFilter) is the lit one, and only that one; no filter lights All")
    void shouldLightTheButtonOfTheFilter() {
        for (String filter : new String[] {"all", "played", "rejected"}) {
            String html = renderWithFilter(filter);

            for (String other : new String[] {"all", "played", "rejected"}) {
                assertThat(html.contains("class=\"btn btn-outline-secondary btn-sm active\" data-list-filter=\"" + other + "\""))
                        .as("%s is lit when the filter is %s", other, filter).isEqualTo(other.equals(filter));
            }
        }
        assertThat(render(List.of(guest(1, "Alpha", "played")), false, Locale.ENGLISH))
                .contains("active\" data-list-filter=\"all\"");
    }

    @Test
    @DisplayName("a party with no genre (the style ANY) shows \"Dowolny\" / \"Any\", never the raw ANY; the column is \"Klimat\"")
    void shouldNameTheVibeOfNoGenre() {
        HistoryEntry any = new HistoryEntry(1L, Instant.parse("2026-09-29T18:00:00Z"), "Song", null, "ANY", "played", null, 5, null);

        assertThat(render(List.of(any), false, PL)).contains(">Dowolny<", ">Klimat<").doesNotContain(">ANY<", ">Vibe<");
        assertThat(render(List.of(any), false, Locale.ENGLISH)).contains(">Any<").doesNotContain(">ANY<");
        assertThat(render(List.of(guest(1, "Alpha", "played")), false, PL)).as("a genre as it was saved").contains(">Pop<");
    }

    @Test
    @DisplayName("\"Wyczyść historię\" posts to its own endpoint and asks first (what goes, that the AI forgets); none with nothing to clear")
    void shouldOfferToClearTheHistory_onlyWhenThereIsOne() {
        String html = render(List.of(guest(1, "Alpha", "played")), false, PL);

        assertThat(html).containsPattern("<form action=\"/dj/dashboard/clear-history\" method=\"post\"[^>]*data-confirm=\"[^\"]*na zawsze[^\"]*AI zapomni");
        assertThat(html).contains("id=\"clearHistoryBtn\"", "🗑 Wyczyść historię");
        assertThat(render(List.of(guest(1, "Alpha", "played")), false, Locale.ENGLISH)).contains("🗑 Clear the history", "for good");
        assertThat(render(List.of(), false, PL)).doesNotContain("clear-history", "clearHistoryBtn");
    }

    private static String renderWithFilter(String filter) {
        Context context = new Context(Locale.ENGLISH);
        context.setVariable("history", List.of(guest(1, "Alpha", "played")));
        context.setVariable("historyHasMore", false);
        context.setVariable("historyNextLimit", 100);
        context.setVariable("historyFilter", filter);
        return engine.process("history", Set.of("historyTableContent"), context);
    }

    @Test
    @DisplayName("a song shows its style and energy, and the \"🔍 Preview\" link to YouTube's search results")
    void shouldRenderASong() {
        String html = render(List.of(guest(1, "Alpha Beta", "played")), false, Locale.ENGLISH);

        assertThat(html).contains(">Pop<", "7/10");
        assertThat(html).contains("href=\"https://www.youtube.com/results?search_query=Alpha+Beta\"", ">🔍 Preview<");
        assertThat(html).doesNotContain(">LINK<", "🎶");
    }

    @Test
    @DisplayName("a song without a link has no \"🔍 Preview\"; one without an energy rating shows a dash, never \"null\"")
    void shouldLeaveOutWhatIsMissing() {
        HistoryEntry bare = new HistoryEntry(1L, java.time.Instant.parse("2026-09-29T18:00:00Z"), "Song", null, "Pop", "rejected",
                null, null, null);
        String html = render(List.of(bare), false, Locale.ENGLISH);

        assertThat(html).doesNotContain("🔍", "badge-energy", ">null<");
        assertThat(html).containsPattern("data-sort-value=\"energy\"[^>]*data-val=\"0\"");
        assertThat(html).contains("—");
    }

    @Test
    @DisplayName("a song shows the guest's own words under it, also when they read like the song's name; none when missing")
    void shouldShowTheGuestsWords() {
        HistoryEntry shrek = new HistoryEntry(1L, java.time.Instant.parse("2026-09-29T18:00:00Z"), "Smash Mouth - All Star",
                null, "Pop", "rejected", "no", 3, "the one from Shrek");
        HistoryEntry same = new HistoryEntry(2L, java.time.Instant.parse("2026-09-29T18:01:00Z"), "Wilki - Baśka",
                null, "Pop", "played", "ok", 7, "wilki baska");
        String html = render(List.of(shrek, same, guest(3, "Without words", "played")), false, Locale.ENGLISH);

        assertThat(html).contains("guest wrote: “the one from Shrek”", "guest wrote: “wilki baska”");
        assertThat(html.split("guest-text", -1)).as("the two rows with words have the line, the one without none").hasSize(3);
    }

    @Test
    @DisplayName("a song shows its votes (the history sorted by them is the party's ranking); one guest's is a grey 1")
    void shouldShowTheVotes() {
        HistoryEntry wanted = new HistoryEntry(1L, java.time.Instant.parse("2026-09-29T18:00:00Z"), "Wanted",
                null, "Pop", "played", "ok", 7, null, 12);
        String html = render(List.of(wanted, guest(2, "Intro", "played")), false, Locale.ENGLISH);

        assertThat(html).contains("<th data-sort=\"votes\" data-sort-first=\"desc\"", ">Votes<");
        assertThat(html).contains("data-sort-value=\"votes\" data-val=\"12\"").containsPattern("text-bg-warning\">12<");
        String intro = html.substring(html.indexOf("data-song-name=\"Intro\""));
        assertThat(intro.substring(0, intro.indexOf("</tr>"))).contains("data-val=\"1\"", "badge text-bg-secondary\">1<").doesNotContain("text-bg-warning");
    }

    @Test
    @DisplayName("a request the DJ skipped has \"↩ Przywróć\" (back to the queue); one the AI rejected, and one that played, have not")
    void shouldOfferToRestoreOnlyWhatTheDjSkipped() {
        java.time.Instant at = java.time.Instant.parse("2026-09-29T18:00:00Z");
        HistoryEntry skipped = new HistoryEntry(41L, at, "Skipped", null, "Pop", "rejected", "Klasyk wesel!", 7, null, 2, at);
        HistoryEntry byTheAi = new HistoryEntry(42L, at, "ByTheAi", null, "Pop", "rejected", "Not tonight", 7, null, 1);
        String html = render(List.of(skipped, byTheAi, guest(43, "Played", "played")), false, Locale.forLanguageTag("pl"));

        assertThat(html.split("action=\"/dj/dashboard/restore\"", -1)).as("one restore form").hasSize(2);
        String row = html.substring(html.indexOf("data-song-name=\"Skipped\""));
        assertThat(row.substring(0, row.indexOf("</tr>"))).contains("action=\"/dj/dashboard/restore\"",
                "name=\"id\" value=\"41\"", "↩ Przywróć", "title=\"Z powrotem do kolejki — pominięte przez pomyłkę\"",
                "⏭ Pominięta przez DJ-a", "Klasyk wesel!");
        String aiRow = html.substring(html.indexOf("data-song-name=\"ByTheAi\""));
        assertThat(aiRow.substring(0, aiRow.indexOf("</tr>"))).as("rejected by the AI, not skipped").doesNotContain("Pominięta")
                .contains("status-rejected", ">ODRZUCONE<", "✖");
        // the AI took it, the DJ skipped it (the owner, 2026-10-07): "skipped" in place of the verdict, not "rejected", not a ✖
        assertThat(row.substring(0, row.indexOf("</tr>"))).contains("status-skipped")
                .doesNotContain("status-rejected", ">ODRZUCONE<", "✖", "ZAAKCEPTOWANE");
    }

    @Test
    @DisplayName("a song name is never rendered as HTML, not even inside an attribute (it comes from guests)")
    void shouldEscapeTheTitle() {
        String html = render(List.of(guest(1, "\"><script>alert(1)</script>", "played")), false, Locale.ENGLISH);

        assertThat(html).doesNotContain("<script>alert(1)</script>");
        assertThat(html).contains("&lt;script&gt;");
    }

    @Test
    @DisplayName("the time of a row is the moment of the event, shown in Polish time; data-val, for sorting, is the same moment in UTC")
    void shouldShowTheTimeOfTheEvent() {
        String html = render(List.of(guest(5, "Alpha", "played")), false, Locale.ENGLISH);

        assertThat(html).contains("29.09.2026 20:05:00");   // 20:05 in Warsaw (summer time, UTC+2)
        assertThat(html).contains("data-val=\"2026-09-29T18:05:00.000Z\"");
    }

    @Test
    @DisplayName("the list has a search box, the three filter buttons (All chosen at first, then Played, Rejected), a count and a scroll box")
    void shouldHaveTheListTools() {
        String html = render(List.of(guest(1, "Alpha", "played")), false, Locale.ENGLISH);

        assertThat(html).contains("data-list", "data-list-search", "data-list-count", "data-nomatch", "list-scroll");
        assertThat(html).containsPattern("class=\"btn btn-outline-secondary btn-sm active\"[^>]*data-list-filter=\"all\"");
        assertThat(html).contains("data-list-filter=\"played\"", "data-list-filter=\"rejected\"");
        assertThat(html).contains(">All<", ">Played<", ">Rejected<", "placeholder=\"Search");
        assertThat(html.split("data-list-filter=", -1)).hasSize(4);             // exactly three buttons
        assertThat(html.indexOf("data-list-filter=\"all\"")).isLessThan(html.indexOf("data-list-filter=\"played\""));
        assertThat(html.indexOf("data-list-filter=\"played\"")).isLessThan(html.indexOf("data-list-filter=\"rejected\""));
    }

    @Test
    @DisplayName("the count is the number of rows, with a \"+\" (also in data-suffix) when older entries exist")
    void shouldShowTheCount() {
        List<HistoryEntry> fifty = IntStream.range(0, 50).mapToObj(i -> guest(i, "Song " + i, "played")).toList();

        String more = render(fifty, true, Locale.ENGLISH);
        String all = render(fifty.subList(0, 12), false, Locale.ENGLISH);

        assertThat(more).containsPattern("data-list-count[^>]*data-suffix=\"\\+\"[^>]*>50\\+<");
        assertThat(all).containsPattern("data-list-count[^>]*>12<");
        assertThat(all).doesNotContain("data-suffix=\"+\"");   // no suffix at all: the script reads a missing one as empty
    }

    @Test
    @DisplayName("\"Show more\" is there only when older entries exist, and carries the limit to ask for")
    void shouldOfferShowMoreOnlyWhenThereAreOlderEntries() {
        List<HistoryEntry> history = List.of(guest(1, "Alpha", "played"));

        String with = render(history, true, Locale.ENGLISH);
        String without = render(history, false, Locale.ENGLISH);

        assertThat(with).containsPattern("data-history-more[^>]*data-limit=\"100\"[^>]*>Show more<");
        assertThat(without).doesNotContain("data-history-more");
    }

    @Test
    @DisplayName("an empty history says so; the tools are still there and the \"nothing matches\" row is hidden")
    void shouldRenderAnEmptyHistory() {
        String html = render(List.of(), false, Locale.ENGLISH);

        assertThat(html).contains("History is empty.");
        assertThat(html).containsPattern("<tr data-nomatch[^>]*class=\"d-none\"");
    }

    @Test
    @DisplayName("Polish texts of the tools and of the link, with every message key resolved")
    void shouldRenderInPolish() {
        String html = render(List.of(guest(1, "Alpha", "played"), guest(2, "Utwór", "rejected")), true, PL);

        assertThat(html).contains(">Wszystkie<", ">Zagrane<", ">Odrzucone<", ">Pokaż więcej<",
                "placeholder=\"Szukaj…\"");
        assertThat(html).contains("Nic nie pasuje.", ">🔍 Podejrzyj<");
        assertThat(html).doesNotContain("??");
    }
}
