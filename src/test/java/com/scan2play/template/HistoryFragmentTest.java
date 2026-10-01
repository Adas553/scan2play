package com.scan2play.template;

import com.scan2play.model.HistoryEntry;
import com.scan2play.model.HistoryEntry.Source;
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
        return new HistoryEntry(Source.GUEST, (long) i, java.time.LocalDateTime.of(2026, 9, 29, 20, i % 60).atZone(com.scan2play.util.Times.DISPLAY_ZONE).toInstant(), songName,
                "https://www.youtube.com/watch?v=hTWKbfoikeg", "hTWKbfoikeg", "Pop", decision, "ok", 7);
    }

    private static HistoryEntry background(int i, String title) {
        return new HistoryEntry(Source.BACKGROUND, (long) i, java.time.LocalDateTime.of(2026, 9, 29, 20, i % 60).atZone(com.scan2play.util.Times.DISPLAY_ZONE).toInstant(), title,
                "https://www.youtube.com/watch?v=dQw4w9WgXcQ", "dQw4w9WgXcQ", null, "played", null, null);
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
        for (String filter : new String[] {"all", "guest", "background", "played", "rejected"}) {
            String html = renderWithFilter(filter);

            for (String other : new String[] {"all", "guest", "background", "played", "rejected"}) {
                assertThat(html.contains("class=\"btn btn-outline-secondary btn-sm active\" data-list-filter=\"" + other + "\""))
                        .as("%s is lit when the filter is %s", other, filter).isEqualTo(other.equals(filter));
            }
        }
        assertThat(render(List.of(guest(1, "Alpha", "played")), false, Locale.ENGLISH))
                .contains("active\" data-list-filter=\"all\"");
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
    @DisplayName("a track of the background playlist is a row like the others: played, with its title and a link to the video")
    void shouldRenderABackgroundTrack() {
        String html = render(List.of(background(7, "Rick Astley - Never Gonna Give You Up")), false, Locale.ENGLISH);

        assertThat(html).containsPattern("<tr[^>]*data-song-name=\"Rick Astley - Never Gonna Give You Up\"[^>]*data-decision=\"played\"");
        assertThat(html).contains("href=\"https://www.youtube.com/watch?v=dQw4w9WgXcQ\"");
        assertThat(html).contains("🎶 Playlist");            // in the vibe column, where a guest's song has its style
        assertThat(html).contains("title=\"🎶 Playlist\"");   // and beside the title, for a narrow screen that hides that column
    }

    @Test
    @DisplayName("a background track has no style, comment or energy: no empty style badge, a dash for the energy")
    void shouldNotInventTheGuestOnlyColumns_forABackgroundTrack() {
        String html = render(List.of(background(7, "Song")), false, Locale.ENGLISH);

        assertThat(html).doesNotContain("badge-energy");
        assertThat(html).containsPattern("data-sort-value=\"energy\"[^>]*data-val=\"0\"");
        assertThat(html).contains("—");
        assertThat(html).doesNotContain("text-bg-secondary\" >");   // no empty style badge
        assertThat(html).doesNotContain(">null<");
    }

    @Test
    @DisplayName("a guest's song shows its style and energy, and no playlist marker")
    void shouldRenderAGuestSong() {
        String html = render(List.of(guest(1, "Alpha", "played")), false, Locale.ENGLISH);

        assertThat(html).contains(">Pop<", "7/10");
        assertThat(html).doesNotContain("🎶");
    }

    @Test
    @DisplayName("a guest's song shows the guest's own words under it when they say something else; a background track never")
    void shouldShowTheGuestsWords() {
        HistoryEntry shrek = new HistoryEntry(Source.GUEST, 1L, java.time.Instant.parse("2026-09-29T18:00:00Z"), "Smash Mouth - All Star",
                null, null, "Pop", "rejected", "no", 3, "the one from Shrek");
        HistoryEntry same = new HistoryEntry(Source.GUEST, 2L, java.time.Instant.parse("2026-09-29T18:01:00Z"), "Wilki - Baśka",
                null, null, "Pop", "played", "ok", 7, "wilki baska");
        String html = render(List.of(shrek, same, background(3, "Intro")), false, Locale.ENGLISH);

        assertThat(html).contains("guest wrote: “the one from Shrek”");
        assertThat(html.split("guest-text", -1)).as("only the first row has the line").hasSize(2);
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
    @DisplayName("the list has a search box, the five filter buttons (All chosen at first, then Guests, Playlist, Played, Rejected), a count and a scroll box")
    void shouldHaveTheListTools() {
        String html = render(List.of(guest(1, "Alpha", "played")), false, Locale.ENGLISH);

        assertThat(html).contains("data-list", "data-list-search", "data-list-count", "data-nomatch", "list-scroll");
        assertThat(html).containsPattern("class=\"btn btn-outline-secondary btn-sm active\"[^>]*data-list-filter=\"all\"");
        assertThat(html).contains("data-list-filter=\"guest\"", "data-list-filter=\"background\"",
                "data-list-filter=\"played\"", "data-list-filter=\"rejected\"");
        assertThat(html).contains(">All<", ">Guests<", ">Playlist<", ">Played<", ">Rejected<", "placeholder=\"Search");
        assertThat(html.split("data-list-filter=", -1)).hasSize(6);             // exactly five buttons
        assertThat(html.indexOf("data-list-filter=\"all\"")).isLessThan(html.indexOf("data-list-filter=\"guest\""));
        assertThat(html.indexOf("data-list-filter=\"guest\"")).isLessThan(html.indexOf("data-list-filter=\"background\""));
        assertThat(html.indexOf("data-list-filter=\"background\"")).isLessThan(html.indexOf("data-list-filter=\"played\""));
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
    @DisplayName("Polish texts of the tools and of the playlist marker, with every message key resolved")
    void shouldRenderInPolish() {
        String html = render(List.of(guest(1, "Alpha", "played"), background(2, "Utwór")), true, PL);

        assertThat(html).contains(">Wszystkie<", ">Goście<", ">Playlista<", ">Zagrane<", ">Odrzucone<", ">Pokaż więcej<",
                "placeholder=\"Szukaj…\"");
        assertThat(html).contains("Nic nie pasuje.", "🎶 Playlista");
        assertThat(html).doesNotContain("??");
    }
}
