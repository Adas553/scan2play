package com.scan2play.template;

import com.scan2play.model.FallbackQueueView;
import com.scan2play.model.FallbackQueueView.Track;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Renders the real {@code fragments/fallback-queue.html} with the real message bundles — the DJ dashboard is behind
 * a login, so this is what catches a broken expression, a missing message key or an unescaped title.
 */
class FallbackQueueFragmentTest {

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

    private static String render(FallbackQueueView queue, Locale locale) {
        Context context = new Context(locale);
        context.setVariable("queue", queue);
        return engine.process("fragments/fallback-queue", context);
    }

    private static FallbackQueueView queue(boolean shuffle, long remaining, Track... tracks) {
        return new FallbackQueueView(true, shuffle, false, remaining, List.of(tracks));
    }

    @Test
    @DisplayName("lists the titles in order, links each to its YouTube video, and marks the first as the next one")
    void shouldListTracksInOrderAndMarkTheNextOne() {
        String html = render(queue(true, 12,
                new Track(1L, "aaaaaaaaaaa", "First Song"), new Track(2L, "bbbbbbbbbbb", "Second Song")), Locale.ENGLISH);

        assertThat(html).contains("First Song", "Second Song");
        assertThat(html.indexOf("First Song")).isLessThan(html.indexOf("Second Song"));
        assertThat(html).contains("https://www.youtube.com/watch?v=aaaaaaaaaaa", "https://www.youtube.com/watch?v=bbbbbbbbbbb");
        assertThat(html).contains("noopener");
        // exactly one "Next" badge, on the first row
        assertThat(html.split("text-bg-success", -1)).hasSize(2);
        assertThat(html.indexOf("text-bg-success")).isGreaterThan(html.indexOf("First Song"))
                .isLessThan(html.indexOf("Second Song"));
    }

    @Test
    @DisplayName("a long queue scrolls inside the list (fixed height + its own scrollbar) instead of stretching the page")
    void shouldScrollInsideTheList() {
        Track[] many = java.util.stream.IntStream.range(0, 500)
                .mapToObj(i -> new Track((long) i, String.format("vid%08d", i), "Song " + i)).toArray(Track[]::new);

        String html = render(queue(true, 500, many), Locale.ENGLISH);

        assertThat(html).contains("max-height: 17rem").contains("overflow-y: auto");
        assertThat(html.split("<li", -1)).hasSize(501);          // every one of the 500 tracks is in the list
        assertThat(html).contains("Song 0", "Song 499");
    }

    @Test
    @DisplayName("a title is never rendered as HTML (it comes from YouTube)")
    void shouldEscapeTitles() {
        String html = render(queue(false, 1, new Track(1L, "aaaaaaaaaaa", "<script>alert('x')</script> & <b>bold</b>")), Locale.ENGLISH);

        assertThat(html).doesNotContain("<script>").doesNotContain("<b>bold</b>");
        assertThat(html).contains("&lt;script&gt;");
    }

    @Test
    @DisplayName("a track without a title shows its YouTube link text instead of an empty row")
    void shouldFallBackToTheVideoLink_whenThereIsNoTitle() {
        String html = render(queue(false, 1, new Track(1L, "aaaaaaaaaaa", null)), Locale.ENGLISH);

        assertThat(html).contains("youtu.be/aaaaaaaaaaa");
    }

    @Test
    @DisplayName("the order caption says whether the queue is random or in playlist order, and how many are left")
    void shouldShowOrderAndRemainingCount() {
        Track track = new Track(1L, "aaaaaaaaaaa", "Song");

        String shuffled = render(queue(true, 12, track), Locale.ENGLISH);
        assertThat(shuffled).contains("Random order").contains("12 left in this round").doesNotContain("Playlist order");

        String ordered = render(queue(false, 12, track), Locale.ENGLISH);
        assertThat(ordered).contains("Playlist order").contains("12 left in this round").doesNotContain("Random order");
    }

    @Test
    @DisplayName("Polish texts come from messages_pl.properties, with the Polish characters intact")
    void shouldRenderPolishTexts() {
        String html = render(queue(true, 12, new Track(1L, "aaaaaaaaaaa", "Piosenka")), PL);

        assertThat(html).contains("Losowa kolejność").contains("Zostało w tej rundzie: 12").contains("Następny");
    }

    @Test
    @DisplayName("without a fallback playlist a hint is shown instead of a list")
    void shouldShowHint_whenThereIsNoPlaylist() {
        String html = render(FallbackQueueView.noPlaylist(true), PL);

        assertThat(html).contains("Ustaw playlistę powyżej").doesNotContain("<ol");
    }

    @Test
    @DisplayName("a playlist without queued tracks explains that the import failed or is still running")
    void shouldShowWarning_whenThePlaylistHasNoTracks() {
        String html = render(new FallbackQueueView(true, true, false, 0, List.of()), Locale.ENGLISH);

        assertThat(html).contains("No tracks in the queue").contains("save the playlist again").doesNotContain("<ol");
    }

    // ---- moving tracks ----

    private static int count(String html, String fragment) {
        return html.split(java.util.regex.Pattern.quote(fragment), -1).length - 1;
    }

    @Test
    @DisplayName("every row has play-next / up / down buttons with the track id; they cannot go beyond the ends of the queue")
    void shouldRenderMoveButtons() {
        String html = render(queue(false, 3, new Track(11L, "aaaaaaaaaaa", "One"), new Track(12L, "bbbbbbbbbbb", "Two"),
                new Track(13L, "ccccccccc", "Three")), Locale.ENGLISH);

        assertThat(count(html, "data-move=\"TOP\"")).isEqualTo(3);
        assertThat(count(html, "data-move=\"UP\"")).isEqualTo(3);
        assertThat(count(html, "data-move=\"DOWN\"")).isEqualTo(3);
        assertThat(count(html, "data-track-id=")).as("the id is on the row, not repeated on every button").isEqualTo(3);
        assertThat(html).contains("data-track-id=\"11\"", "data-track-id=\"12\"", "data-track-id=\"13\"");
        // the first track cannot go up or be played "next" any earlier, the last cannot go down: 3 buttons disabled
        assertThat(count(html, "disabled=\"disabled\"")).isEqualTo(3);
        assertThat(html).contains("title=\"Play next\"", "title=\"Move up\"", "title=\"Move down\"");
    }

    @Test
    @DisplayName("the buttons speak Polish in the Polish dashboard")
    void shouldRenderPolishButtonTitles() {
        String html = render(queue(false, 2, new Track(1L, "aaaaaaaaaaa", "Raz"), new Track(2L, "bbbbbbbbbbb", "Dwa")), PL);

        assertThat(html).contains("Zagraj jako następny", "Przesuń wyżej", "Przesuń niżej");
    }

    @Test
    @DisplayName("a single track cannot move at all: all its move buttons are disabled")
    void shouldDisableEveryButton_forASingleTrack() {
        String html = render(queue(false, 1, new Track(1L, "aaaaaaaaaaa", "Only one")), Locale.ENGLISH);

        assertThat(count(html, "<button")).isEqualTo(4);                       // the three moves, and the skip button
        assertThat(count(html, "disabled=\"disabled\"")).isEqualTo(3);         // (skipping the only track is fine: the round starts over)
    }

    @Test
    @DisplayName("the list tells the dashboard whether the order was changed by hand (data-manual)")
    void shouldFlagManualOrder() {
        Track track = new Track(1L, "aaaaaaaaaaa", "Song");

        assertThat(render(queue(true, 1, track), Locale.ENGLISH)).contains("data-manual=\"false\"");
        assertThat(render(new FallbackQueueView(true, true, true, 1, List.of(track)), Locale.ENGLISH))
                .contains("data-manual=\"true\"");
    }

    @Test
    @DisplayName("the order caption says 'changed by hand' once the DJ has moved tracks — for random and playlist order alike")
    void shouldSayTheOrderWasChangedByHand() {
        Track track = new Track(1L, "aaaaaaaaaaa", "Song");

        assertThat(render(new FallbackQueueView(true, true, true, 1, List.of(track)), Locale.ENGLISH))
                .contains("Random order, changed by hand");
        assertThat(render(new FallbackQueueView(true, false, true, 1, List.of(track)), Locale.ENGLISH))
                .contains("Playlist order, changed by hand");
        assertThat(render(new FallbackQueueView(true, false, true, 1, List.of(track)), PL))
                .contains("Kolejność z playlisty, zmieniona ręcznie");
        assertThat(render(new FallbackQueueView(true, true, true, 1, List.of(track)), PL))
                .contains("Losowa kolejność, zmieniona ręcznie");
        // without manual moves the plain captions stay
        assertThat(render(queue(true, 1, track), Locale.ENGLISH)).contains("Random order").doesNotContain("changed by hand");
    }

    @Test
    @DisplayName("without tracks there are no buttons")
    void shouldRenderNoButtons_whenThereAreNoTracks() {
        assertThat(render(new FallbackQueueView(true, true, false, 0, List.of()), Locale.ENGLISH)).doesNotContain("<button");
        assertThat(render(FallbackQueueView.noPlaylist(true), Locale.ENGLISH)).doesNotContain("<button");
    }

    // ---- dragging ----

    @Test
    @DisplayName("the title is plain text (a press on it starts a drag); the link to YouTube is a small arrow beside the buttons")
    void shouldKeepTheLinkOutOfTheTitle() {
        String html = render(queue(false, 2, new Track(11L, "aaaaaaaaaaa", "First Song"), new Track(12L, "bbbbbbbbbbb", "Second Song")),
                Locale.ENGLISH);

        // exactly one link per row, and it is the arrow
        assertThat(count(html, "<a ")).isEqualTo(2);
        assertThat(html).contains("href=\"https://www.youtube.com/watch?v=aaaaaaaaaaa\"", "href=\"https://www.youtube.com/watch?v=bbbbbbbbbbb\"");
        assertThat(count(html, "\u2197")).isEqualTo(2);
        assertThat(html).contains("title=\"Open on YouTube\"", "draggable=\"false\"", "noopener");
        // the title is not inside a link
        assertThat(html).doesNotContainPattern("<a [^>]*>\\s*First Song");
        assertThat(html).contains("First Song").contains("Second Song");
    }

    @Test
    @DisplayName("the list is set up for dragging: grab cursor, no text selection, no touch callout")
    void shouldPrepareTheListForDragging() {
        String html = render(queue(false, 1, new Track(1L, "aaaaaaaaaaa", "Song")), Locale.ENGLISH);

        assertThat(html).contains("cursor: grab", "user-select: none", "-webkit-touch-callout: none");
    }

    @Test
    @DisplayName("the arrow link speaks Polish in the Polish dashboard")
    void shouldRenderPolishLinkTitle() {
        String html = render(queue(false, 1, new Track(1L, "aaaaaaaaaaa", "Piosenka")), PL);

        assertThat(html).contains("title=\"Otwórz na YouTube\"");
    }

    // ---- skipping a track for this round ----

    @Test
    @DisplayName("every row has a skip button that says what it does (dashboard.js handles data-skip); it is not one of the moves")
    void shouldGiveEveryRowASkipButton() {
        FallbackQueueView view = queue(false, 2, new Track(1L, "aaaaaaaaaaa", "One"), new Track(2L, "bbbbbbbbbbb", "Two"));

        String english = render(view, Locale.ENGLISH);
        assertThat(count(english, "data-skip")).isEqualTo(2);
        assertThat(english).contains("title=\"Skip this round — the track comes back when the playlist starts over\"");
        assertThat(render(view, PL)).contains("title=\"Pomiń w tej rundzie — utwór wróci, gdy playlista zacznie się od nowa\"");
        // the skip button is enabled even for the first and the last row: the first track can be skipped like any other
        assertThat(english).doesNotContain("data-skip disabled");
    }

    @Test
    @DisplayName("how many tracks the DJ has skipped in this round is shown next to how many are left — and only when there are some")
    void shouldShowHowManyTracksWereSkipped() {
        Track track = new Track(1L, "aaaaaaaaaaa", "Song");

        assertThat(render(new FallbackQueueView(true, false, false, 3, 2, List.of(track)), Locale.ENGLISH))
                .contains("3 left in this round", "Skipped this round: 2");
        assertThat(render(new FallbackQueueView(true, false, false, 3, 2, List.of(track)), PL))
                .contains("Zostało w tej rundzie: 3", "Pominięte w tej rundzie: 2");
        assertThat(render(new FallbackQueueView(true, false, false, 3, 0, List.of(track)), Locale.ENGLISH))
                .contains("3 left in this round").doesNotContain("Skipped");
    }
}
