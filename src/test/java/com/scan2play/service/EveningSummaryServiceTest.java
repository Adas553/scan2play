package com.scan2play.service;

import com.scan2play.entity.SongRequestEntity;
import com.scan2play.repository.SongRequestRepository;
import com.scan2play.util.Times;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * "Podsumowanie wieczoru": which requests make an evening, how they are counted, the most wanted songs, the hours and the CSV.
 */
class EveningSummaryServiceTest {

    private static final LocalDate EVENING = LocalDate.of(2026, 10, 3);

    /** A moment of the evening of 03.10 in Polish time: "20:15", or "01:30" — the night after. */
    private static Instant at(String clock) {
        LocalDateTime time = EVENING.atTime(java.time.LocalTime.parse(clock));
        if (time.toLocalTime().isBefore(EveningSummaryService.EVENING_STARTS)) {
            time = time.plusDays(1);
        }
        return time.atZone(Times.DISPLAY_ZONE).toInstant();
    }

    private static SongRequestEntity request(String song, String decision, String asked, String played, int votes) {
        return SongRequestEntity.builder().partyCode("SUMM1").songName(song).decision(decision).requestedAt(at(asked))
                .playedAt(played == null ? null : at(played)).votes(votes).build();
    }

    private static SongRequestEntity withTips(SongRequestEntity request, int tips) {
        request.setTips(tips);
        return request;
    }

    @Test
    void anEveningRunsFromSixToSix_inPolishTime() {
        assertThat(EveningSummaryService.eveningOf(at("20:00"))).isEqualTo(EVENING);
        assertThat(EveningSummaryService.eveningOf(at("05:59"))).isEqualTo(EVENING);
        assertThat(EveningSummaryService.eveningOf(at("06:00"))).isEqualTo(EVENING);
        assertThat(EveningSummaryService.eveningOf(EVENING.plusDays(1).atTime(6, 0).atZone(Times.DISPLAY_ZONE).toInstant()))
                .isEqualTo(EVENING.plusDays(1));
    }

    /**
     * The nights the clocks change: six hours back on the clock, as {@code findEvenings} counts in SQL ({@code AT TIME ZONE … -
     * INTERVAL '6 hours'}) and as {@link EveningSummaryService#requests} reads (from 06:00 on the clock) — not six hours of time,
     * which moved a request by the hour that night (review 2026-10-14).
     */
    @Test
    void anEveningRunsFromSixToSix_onTheClock_alsoTheNightsTheClocksChange() {
        java.time.LocalDate spring = java.time.LocalDate.of(2026, 3, 29);   // 02:00 → 03:00
        java.time.LocalDate autumn = java.time.LocalDate.of(2026, 10, 25);  // 03:00 → 02:00
        assertThat(EveningSummaryService.eveningOf(spring.atTime(6, 30).atZone(Times.DISPLAY_ZONE).toInstant())).isEqualTo(spring);
        assertThat(EveningSummaryService.eveningOf(spring.atTime(5, 59).atZone(Times.DISPLAY_ZONE).toInstant()))
                .isEqualTo(spring.minusDays(1));
        assertThat(EveningSummaryService.eveningOf(autumn.atTime(5, 59).atZone(Times.DISPLAY_ZONE).toInstant()))
                .isEqualTo(autumn.minusDays(1));
        assertThat(EveningSummaryService.eveningOf(autumn.atTime(6, 0).atZone(Times.DISPLAY_ZONE).toInstant())).isEqualTo(autumn);
    }

    @Test
    void theRequestsOfAnEvening_areReadFromSixToSix_bounded() {
        SongRequestRepository repository = mock(SongRequestRepository.class);
        new EveningSummaryService(repository, org.mockito.Mockito.mock(DjLibraryService.class)).requests("SUMM1", EVENING);

        verify(repository).findRequestedBetween(eq("SUMM1"), eq(at("06:00")), eq(at("05:59").plusSeconds(60)),
                eq(Pageable.ofSize(EveningSummaryService.MAX_REQUESTS)));
    }

    @Test
    void theEvenings_comeFromTheQuery_latestFirst() {
        SongRequestRepository repository = mock(SongRequestRepository.class);
        when(repository.findEvenings("SUMM1", EveningSummaryService.MAX_EVENINGS))
                .thenReturn(List.of(new Object[]{"2026-10-03", 42L}, new Object[]{"2026-09-26", 7L}));

        assertThat(new EveningSummaryService(repository, org.mockito.Mockito.mock(DjLibraryService.class)).evenings("SUMM1")).containsExactly(
                new EveningSummaryService.Evening(EVENING, 42), new EveningSummaryService.Evening(LocalDate.of(2026, 9, 26), 7));
    }

    @Test
    void theCounts_splitTheRequestsByHowTheyEnded() {
        SongRequestEntity skipped = request("Skipped", "rejected", "21:00", null, 2);
        skipped.setSkippedAt(at("21:30"));
        SongRequestEntity cleared = request("Cleared", "rejected", "23:00", null, 1);
        cleared.setClearedAt(at("02:00"));
        List<SongRequestEntity> requests = List.of(
                request("Played early", "played", "20:10", "20:30", 3),
                withTips(request("Played late", "played", "20:20", "01:15", 5), 2),
                request("Waiting", "accepted", "01:00", null, 1),
                request("Rejected by the AI", "rejected", "22:00", null, 1),
                withTips(skipped, 1), cleared);

        EveningSummaryService.Summary summary = EveningSummaryService.summarize(EVENING, requests);

        assertThat(summary.requests()).isEqualTo(6);
        assertThat(summary.played()).isEqualTo(2);
        assertThat(summary.waiting()).isEqualTo(1);
        assertThat(summary.skipped()).isEqualTo(1);
        assertThat(summary.cleared()).isEqualTo(1);
        assertThat(summary.rejectedByAi()).isEqualTo(1);
        // the votes of what the AI let through: 3 + 5 + 1 + 2 + 1
        assertThat(summary.votes()).isEqualTo(12);
        assertThat(summary.tips()).isEqualTo(3);
        assertThat(summary.first()).isEqualTo(at("20:10"));
        assertThat(summary.last()).isEqualTo(at("01:15"));
        assertThat(summary.setlist()).extracting(SongRequestEntity::getSongName).containsExactly("Played early", "Played late");
        assertThat(summary.top()).extracting(SongRequestEntity::getSongName)
                .containsExactly("Played late", "Played early", "Skipped", "Cleared", "Waiting");
        // wanted, not heard: what the AI let through and did not play
        assertThat(summary.missed()).extracting(SongRequestEntity::getSongName).containsExactly("Skipped", "Cleared", "Waiting");
        // the played ones waited 20 and 295 minutes
        assertThat(summary.averageWaitMinutes()).isEqualTo(158);
        assertThat(summary.longestWaitMinutes()).isEqualTo(295);
        assertThat(summary.truncated()).isFalse();
    }

    /** Requests sent at the venue's kiosk (V56): counted in the requests and apart, as the DJ's own. */
    @Test
    void theKiosksRequests_areCountedApart() {
        SongRequestEntity kiosk = request("Kult - Arahja", "played", "21:00", "21:10", 1);
        kiosk.setViaKiosk(true);
        EveningSummaryService.Summary summary = EveningSummaryService.summarize(EVENING,
                List.of(request("Wilki - Baśka", "played", "20:00", "20:30", 2), kiosk));

        assertThat(summary.requests()).isEqualTo(2);
        assertThat(summary.viaKiosk()).isEqualTo(1);
        assertThat(summary.votes()).as("a kiosk's guest's vote is a guest's").isEqualTo(3);
    }

    /** A song the DJ added (V44): one of the requests, counted apart; its first vote is the DJ's, so the guests' votes leave it out. */
    @Test
    void aSongTheDjAdded_isCountedApart_andItsFirstVoteIsNotAGuests() {
        SongRequestEntity added = request("Kult - Arahja", "played", "21:00", "21:10", 3);
        added.setAddedByDj(true);
        List<SongRequestEntity> requests = List.of(request("Wilki - Baśka", "played", "20:00", "20:30", 2), added);

        EveningSummaryService.Summary summary = EveningSummaryService.summarize(EVENING, requests);

        assertThat(summary.requests()).isEqualTo(2);
        assertThat(summary.addedByDj()).isEqualTo(1);
        assertThat(summary.votes()).as("2 + the guests' 2 on the DJ's song").isEqualTo(4);
        ResourceBundleMessageSource messages = new ResourceBundleMessageSource();
        messages.setBasename("messages");
        messages.setDefaultEncoding("UTF-8");
        messages.setFallbackToSystemLocale(false);
        String[] lines = EveningSummaryService.toCsv(requests, messages, Locale.forLanguageTag("pl")).split("\r\n");
        assertThat(lines[1]).endsWith(";");
        assertThat(lines[2]).endsWith(";\"tak\"");
    }

    @Test
    void theTopSongs_areTen_theMostVotesFirst_theEarlierAmongEqualOnes() {
        List<SongRequestEntity> requests = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            requests.add(request("Song " + i, "accepted", "%02d:%02d".formatted(20 + i / 6, (i % 6) * 10), null, i == 7 ? 9 : 1));
        }

        assertThat(EveningSummaryService.summarize(EVENING, requests).top()).extracting(SongRequestEntity::getSongName)
                .hasSize(10).startsWith("Song 7", "Song 0", "Song 1");
    }

    @Test
    void theHalfHours_runInTheEveningsOrder_overMidnight_withTheQuietOnes() {
        List<SongRequestEntity> requests = List.of(
                request("a", "accepted", "22:05", null, 1),
                request("b", "accepted", "22:40", null, 1),
                request("c", "accepted", "22:59", null, 1),
                request("d", "accepted", "00:10", null, 1),
                request("e", "accepted", "21:50", null, 1));

        EveningSummaryService.Summary summary = EveningSummaryService.summarize(EVENING, requests);

        assertThat(summary.slots()).extracting(EveningSummaryService.TimeSlot::label)
                .containsExactly("21:30", "22:00", "22:30", "23:00", "23:30", "00:00");
        assertThat(summary.slots()).extracting(EveningSummaryService.TimeSlot::requests).containsExactly(1L, 1L, 2L, 0L, 0L, 1L);
        assertThat(summary.busiestSlot()).isEqualTo("22:30–23:00");
        assertThat(summary.busiestSlotRequests()).isEqualTo(2);
        assertThat(EveningSummaryService.summarize(EVENING, List.of(request("x", "accepted", "23:45", null, 1))).busiestSlot())
                .isEqualTo("23:30–00:00");
    }

    @Test
    void theArtists_ofWhatTheAiLetThrough_theMostVotesFirst_thenTheMostSongs() {
        List<SongRequestEntity> requests = List.of(
                request("Eminem - Lose Yourself", "played", "20:00", "20:10", 1),
                request("eminem – Without Me", "accepted", "20:05", null, 1),   // an older name, with "–"
                request("Sanah - Szampan", "played", "20:10", "20:20", 2),
                request("Wilki - Baśka", "accepted", "20:15", null, 1),
                request("Nirvana - Smells Like Teen Spirit", "rejected", "20:20", null, 5),
                request("no artist here", "accepted", "20:25", null, 9));

        assertThat(EveningSummaryService.summarize(EVENING, requests).artists()).containsExactly(
                new EveningSummaryService.ArtistCount("Eminem", 2, 2),
                new EveningSummaryService.ArtistCount("Sanah", 1, 2),
                new EveningSummaryService.ArtistCount("Wilki", 1, 1));
    }

    @Test
    void anEveningWithoutRequests_isEmpty() {
        EveningSummaryService.Summary summary = EveningSummaryService.summarize(EVENING, List.of());

        assertThat(summary.requests()).isZero();
        assertThat(summary.slots()).isEmpty();
        assertThat(summary.busiestSlot()).isNull();
        assertThat(summary.top()).isEmpty();
        assertThat(summary.missed()).isEmpty();
        assertThat(summary.artists()).isEmpty();
        assertThat(summary.averageWaitMinutes()).isNull();
    }

    @Test
    void theCsv_hasAHeader_aRowPerRequest_andKeepsAGuestsFormulaAsText() {
        ResourceBundleMessageSource messages = new ResourceBundleMessageSource();
        messages.setBasename("messages");
        messages.setDefaultEncoding("UTF-8");
        messages.setFallbackToSystemLocale(false);
        SongRequestEntity played = request("Varius Manx - Orła cień", "played", "21:00", "21:30", 4);
        played.setGuestText("orła cień \"ten\"");
        played.setDjComment("Klasyk!");
        played.setRequestNumber(27);
        played.setTips(2);
        SongRequestEntity formula = request("=HYPERLINK(\"http://x\")", "rejected", "22:00", null, 1);
        formula.setGuestText("@SUM(1;2)");

        String csv = EveningSummaryService.toCsv(List.of(played, formula), messages, Locale.forLanguageTag("pl"));

        String[] lines = csv.split("\r\n");
        assertThat(lines[0]).isEqualTo((char) 0xFEFF
                + "Numer;Prośba;Zagrana;Piosenka;Gość napisał;Głosy;Napiwki;Status;Komentarz AI;Dodana przez DJ-a");
        assertThat(lines[1]).isEqualTo("27;\"2026-10-03 21:00\";\"2026-10-03 21:30\";\"Varius Manx - Orła cień\";"
                + "\"orła cień \"\"ten\"\"\";4;2;\"zagrana\";\"Klasyk!\";");
        // a request the AI rejected has no number
        assertThat(lines[2]).isEqualTo(";\"2026-10-03 22:00\";;\"'=HYPERLINK(\"\"http://x\"\")\";\"'@SUM(1;2)\";1;0;"
                + "\"odrzucona przez AI\";;");
        assertThat(lines).hasSize(3);
    }

    @Test
    void aCell_isQuoted_onOneLine() {
        assertThat(EveningSummaryService.cell(null)).isEmpty();
        assertThat(EveningSummaryService.cell("a\r\nb")).isEqualTo("\"a  b\"");
        assertThat(EveningSummaryService.cell("-1")).isEqualTo("\"'-1\"");
        assertThat(EveningSummaryService.cell("+48")).isEqualTo("\"'+48\"");
    }

    @Test
    void theStatus_ofEachKindOfRequest() {
        assertThat(EveningSummaryService.statusOf(request("x", "accepted", "20:00", null, 1)))
                .isEqualTo(EveningSummaryService.Status.WAITING);
        assertThat(EveningSummaryService.statusOf(request("x", "played", "20:00", "20:10", 1)))
                .isEqualTo(EveningSummaryService.Status.PLAYED);
        assertThat(EveningSummaryService.statusOf(request("x", "rejected", "20:00", null, 1)))
                .isEqualTo(EveningSummaryService.Status.REJECTED_BY_AI);
    }

    // ---- the DJ's library (V42) ----

    /**
     * "Nie masz w bibliotece": the songs the AI let through that the library has no song of ("✗") or only a similar one ("?"), each
     * once (two requests of one song are one line, their votes added), the most wanted first; what the DJ has and what the AI
     * rejected are not there.
     */
    @Test
    void theSongsNotInTheLibrary_mostWantedFirst_eachOnce() {
        SongRequestEntity wilki = request("Wilki - Baśka", "played", "20:00", "20:10", 3);
        SongRequestEntity wilkiAgain = request("Wilki - Baska", "accepted", "23:00", null, 2);
        SongRequestEntity have = request("Daft Punk - One More Time", "accepted", "21:00", null, 9);
        SongRequestEntity similar = request("Dżem - Baśka", "accepted", "21:30", null, 1);
        SongRequestEntity rejected = request("Zenek - Przez twe oczy", "rejected", "22:00", null, 7);
        java.util.Map<com.scan2play.util.LibraryKey, com.scan2play.model.LibraryMark> marks = java.util.Map.of(
                com.scan2play.util.LibraryKey.of("Wilki - Baśka"), com.scan2play.model.LibraryMark.MISSING,
                com.scan2play.util.LibraryKey.of("Daft Punk - One More Time"), com.scan2play.model.LibraryMark.HAVE,
                com.scan2play.util.LibraryKey.of("Dżem - Baśka"), com.scan2play.model.LibraryMark.SIMILAR,
                com.scan2play.util.LibraryKey.of("Zenek - Przez twe oczy"), com.scan2play.model.LibraryMark.MISSING);

        List<EveningSummaryService.LibraryGap> gaps = EveningSummaryService.libraryGaps(List.of(wilki, wilkiAgain, have, similar, rejected), marks);

        assertThat(gaps).containsExactly(
                new EveningSummaryService.LibraryGap("Wilki - Baśka", 5, 2, com.scan2play.model.LibraryMark.MISSING),
                new EveningSummaryService.LibraryGap("Dżem - Baśka", 1, 1, com.scan2play.model.LibraryMark.SIMILAR));
    }

    /** The CSV of a party with a library: one more column, how each request stands against the library now. */
    @Test
    void theCsv_saysTheLibrary_whenThereIsOne() {
        org.springframework.context.support.ResourceBundleMessageSource messages = new org.springframework.context.support.ResourceBundleMessageSource();
        messages.setBasename("messages");
        messages.setDefaultEncoding("UTF-8");
        messages.setFallbackToSystemLocale(false);
        SongRequestEntity have = request("Wilki - Baśka", "played", "21:00", "21:30", 1);
        have.setId(1L);
        SongRequestEntity similar = request("Dżem - Baśka", "accepted", "21:10", null, 1);
        similar.setId(2L);
        SongRequestEntity missing = request("sanah - Szampan", "accepted", "21:20", null, 1);
        missing.setId(3L);

        String[] lines = EveningSummaryService.toCsv(List.of(have, similar, missing), java.util.Map.of(1L, com.scan2play.model.LibraryMark.HAVE,
                2L, com.scan2play.model.LibraryMark.SIMILAR, 3L, com.scan2play.model.LibraryMark.MISSING), messages,
                Locale.forLanguageTag("pl")).split("\r\n");

        assertThat(lines[0]).endsWith(";Komentarz AI;Dodana przez DJ-a;W bibliotece");
        assertThat(lines[1]).endsWith(";\"tak\"");
        assertThat(lines[2]).endsWith(";\"podobny\"");
        assertThat(lines[3]).endsWith(";\"nie\"");
    }
}
