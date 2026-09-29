package com.scan2play.template;

import com.scan2play.entity.SongRequestEntity;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.time.LocalDateTime;
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

    private static SongRequestEntity request(int i, String songName, String decision) {
        return SongRequestEntity.builder().id((long) i).partyCode("ABC12").songName(songName).style("Pop")
                .decision(decision).djComment("ok").energyLevel(7).requestedAt(LocalDateTime.of(2026, 9, 29, 20, i % 60))
                .trackUrl("https://www.youtube.com/watch?v=hTWKbfoikeg").build();
    }

    private static String render(List<SongRequestEntity> history, boolean hasMore, Locale locale) {
        Context context = new Context(locale);
        context.setVariable("history", history);
        context.setVariable("historyHasMore", hasMore);
        context.setVariable("historyNextLimit", 100);
        return engine.process("history", Set.of("historyTableContent"), context);
    }

    @Test
    @DisplayName("every row carries its song name and decision, for the search box and the Played / Rejected filter")
    void shouldMarkEachRowForTheFilters() {
        String html = render(List.of(request(1, "Alpha", "played"), request(2, "Beta", "rejected")), false, Locale.ENGLISH);

        assertThat(html).containsPattern("<tr[^>]*data-song-name=\"Alpha\"[^>]*data-decision=\"played\"");
        assertThat(html).containsPattern("<tr[^>]*data-song-name=\"Beta\"[^>]*data-decision=\"rejected\"");
    }

    @Test
    @DisplayName("a song name is never rendered as HTML, not even inside an attribute (it comes from guests)")
    void shouldEscapeTheSongName() {
        String html = render(List.of(request(1, "\"><script>alert(1)</script>", "played")), false, Locale.ENGLISH);

        assertThat(html).doesNotContain("<script>alert(1)</script>");
        assertThat(html).contains("&lt;script&gt;");
    }

    @Test
    @DisplayName("the list has a search box, the three filter buttons (All chosen at first), a count and a scroll box")
    void shouldHaveTheListTools() {
        String html = render(List.of(request(1, "Alpha", "played")), false, Locale.ENGLISH);

        assertThat(html).contains("data-list", "data-list-search", "data-list-count", "data-nomatch", "list-scroll");
        assertThat(html).containsPattern("class=\"btn btn-outline-secondary active\"[^>]*data-list-filter=\"all\"");
        assertThat(html).contains("data-list-filter=\"played\"", "data-list-filter=\"rejected\"");
        assertThat(html).contains(">All<", ">Played<", ">Rejected<", "placeholder=\"Search");
    }

    @Test
    @DisplayName("the count is the number of rows, with a \"+\" (also in data-suffix) when older requests exist")
    void shouldShowTheCount() {
        List<SongRequestEntity> fifty = IntStream.range(0, 50).mapToObj(i -> request(i, "Song " + i, "played")).toList();

        String more = render(fifty, true, Locale.ENGLISH);
        String all = render(fifty.subList(0, 12), false, Locale.ENGLISH);

        assertThat(more).containsPattern("data-list-count[^>]*data-suffix=\"\\+\"[^>]*>50\\+<");
        assertThat(all).containsPattern("data-list-count[^>]*>12<");
        assertThat(all).doesNotContain("data-suffix=\"+\"");   // no suffix at all: the script reads a missing one as empty
    }

    @Test
    @DisplayName("\"Show more\" is there only when older requests exist, and carries the limit to ask for")
    void shouldOfferShowMoreOnlyWhenThereAreOlderRequests() {
        List<SongRequestEntity> history = List.of(request(1, "Alpha", "played"));

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
    @DisplayName("Polish texts of the tools, with every message key resolved")
    void shouldRenderInPolish() {
        String html = render(List.of(request(1, "Alpha", "played")), true, PL);

        assertThat(html).contains(">Wszystkie<", ">Zagrane<", ">Odrzucone<", ">Pokaż więcej<", "placeholder=\"Szukaj…\"");
        assertThat(html).contains("Nic nie pasuje.");
        assertThat(html).doesNotContain("??");
    }
}
