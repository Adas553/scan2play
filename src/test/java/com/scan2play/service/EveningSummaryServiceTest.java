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
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
        new EveningSummaryService(repository, org.mockito.Mockito.mock(DjLibraryService.class), mock(com.scan2play.repository.EveningSummaryRepository.class)).requests("SUMM1", EVENING);

        verify(repository).findRequestedBetween(eq("SUMM1"), eq(at("06:00")), eq(at("05:59").plusSeconds(60)),
                eq(Pageable.ofSize(EveningSummaryService.MAX_REQUESTS)));
    }

    /**
     * An evening is its requests still there and the songs kept of the ones gone (V61): together, the first asked first, a song
     * kept back as the request the summary counts — its status as the decision and the moments, no guest's words — and a request
     * in both counted once, as it is now.
     */
    @Test
    void anEvening_isItsRequests_andTheSongsKeptOfTheOnesGone() {
        SongRequestRepository repository = mock(SongRequestRepository.class);
        com.scan2play.repository.EveningSummaryRepository kept = mock(com.scan2play.repository.EveningSummaryRepository.class);
        SongRequestEntity live = request("Sanah - Szampan", "accepted", "23:00", null, 2);
        live.setId(9L);
        SongRequestEntity both = request("Wilki - Baśka", "played", "21:00", "21:10", 5);
        both.setId(3L);
        when(repository.findRequestedBetween(eq("SUMM1"), any(), any(), any())).thenReturn(List.of(live, both));
        when(kept.findSongsBetween(eq("SUMM1"), any(), any(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(List.of(
                new Object[]{1L, "Varius Manx - Orła cień", java.sql.Timestamp.from(at("20:00")), java.sql.Timestamp.from(at("20:30")), "PLAYED", 4, 1, 2, false, true},
                new Object[]{2L, null, java.time.OffsetDateTime.ofInstant(at("20:10"), Times.DISPLAY_ZONE), null, "REJECTED_BY_AI", 1, 0, 0, false, false, "DUPLICATE"},
                new Object[]{3L, "Wilki - Baśka", at("21:00"), null, "WAITING", 1, 0, 0, false, false},
                new Object[]{4L, "Kult - Arahja", at("22:00"), null, "SKIPPED", 3, 0, 0, true, false},
                new Object[]{5L, "Bajm - Biała armia", at("22:30"), null, "CLEARED", 2, 0, 0, false, false}));

        List<SongRequestEntity> requests = new EveningSummaryService(repository, mock(DjLibraryService.class), kept).requests("SUMM1", EVENING);

        assertThat(requests).extracting(SongRequestEntity::getId).containsExactly(1L, 2L, 3L, 4L, 5L, 9L);
        assertThat(requests).extracting(EveningSummaryService::statusOf).containsExactly(EveningSummaryService.Status.PLAYED,
                EveningSummaryService.Status.REJECTED_BY_AI, EveningSummaryService.Status.PLAYED, EveningSummaryService.Status.SKIPPED,
                EveningSummaryService.Status.CLEARED, EveningSummaryService.Status.WAITING);
        SongRequestEntity first = requests.getFirst();
        assertThat(List.of(first.getPlayedAt(), first.getVotes(), first.getTips(), first.getDedications(), first.isViaKiosk(), first.getGuestText() == null))
                .containsExactly(at("20:30"), 4, 1, 2, true, true);
        assertThat(requests.get(2).getVotes()).as("the request still there counts, not its copy").isEqualTo(5);
        assertThat(requests.get(3).isAddedByDj()).isTrue();
        assertThat(requests.get(1).getRejectReason()).isEqualTo(com.scan2play.model.RejectReason.DUPLICATE);
        EveningSummaryService.Summary summary = EveningSummaryService.summarize(EVENING, requests);
        assertThat(List.of(summary.played(), summary.rejectedByAi(), summary.skipped(), summary.cleared(), summary.waiting()))
                .containsExactly(2, 1, 1, 1, 1);
    }

    /**
     * Why the AI rejected (V62): counted apart — played already, off the vibe, the requests of before neither —, and the songs it
     * rejected each once with how many asked, the last reason, the most asked first; a kept one without a name left out.
     */
    @Test
    void theAisRejections_byWhy_andTheSongsItRejected() {
        SongRequestEntity again = request("Zenek Martyniuk - Przekorny los", "rejected", "22:00", null, 1);
        again.setRejectReason(com.scan2play.model.RejectReason.DUPLICATE);
        SongRequestEntity againLater = request("zenek martyniuk – przekorny los", "rejected", "23:00", null, 1);
        againLater.setRejectReason(com.scan2play.model.RejectReason.DUPLICATE);
        SongRequestEntity offVibe = request("Nirvana - Smells Like Teen Spirit", "rejected", "21:00", null, 1);
        offVibe.setRejectReason(com.scan2play.model.RejectReason.OFF_VIBE);
        SongRequestEntity before = request("Metallica - One", "rejected", "20:30", null, 1);
        SongRequestEntity keptWithoutName = request(null, "rejected", "20:40", null, 1);
        keptWithoutName.setRejectReason(com.scan2play.model.RejectReason.OFF_VIBE);
        SongRequestEntity skipped = request("Kult - Arahja", "rejected", "21:30", null, 1);
        skipped.setSkippedAt(at("21:40"));
        skipped.setRejectReason(com.scan2play.model.RejectReason.OFF_VIBE);   // not the AI's: skipped by the DJ

        EveningSummaryService.Summary summary = EveningSummaryService.summarize(EVENING,
                List.of(request("Zenek Martyniuk - Przekorny los", "played", "20:00", "20:10", 3), again, againLater, offVibe, before,
                        keptWithoutName, skipped));

        assertThat(List.of(summary.rejectedByAi(), summary.rejectedAsPlayed(), summary.rejectedOffVibe())).containsExactly(5, 2, 2);
        assertThat(summary.rejected()).extracting(EveningSummaryService.RejectedSong::name, EveningSummaryService.RejectedSong::requests,
                EveningSummaryService.RejectedSong::reason).containsExactly(
                org.assertj.core.groups.Tuple.tuple("Zenek Martyniuk - Przekorny los", 2, com.scan2play.model.RejectReason.DUPLICATE),
                org.assertj.core.groups.Tuple.tuple("Metallica - One", 1, null),
                org.assertj.core.groups.Tuple.tuple("Nirvana - Smells Like Teen Spirit", 1, com.scan2play.model.RejectReason.OFF_VIBE));
    }

    /** "Ostatnie wieczory": up to eight around the shown one — the latest eight when it is among them, else it in the middle. */
    @Test
    void theComparedEvenings_areEightAroundTheShownOne() {
        List<EveningSummaryService.Evening> evenings = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            evenings.add(new EveningSummaryService.Evening(EVENING.minusWeeks(i), i));
        }
        assertThat(EveningSummaryService.compared(evenings, EVENING)).extracting(EveningSummaryService.Evening::votes)
                .containsExactly(0L, 1L, 2L, 3L, 4L, 5L, 6L, 7L);
        assertThat(EveningSummaryService.compared(evenings, EVENING.minusWeeks(10))).extracting(EveningSummaryService.Evening::votes)
                .containsExactly(6L, 7L, 8L, 9L, 10L, 11L, 12L, 13L);
        assertThat(EveningSummaryService.compared(evenings, EVENING.minusWeeks(19))).extracting(EveningSummaryService.Evening::votes)
                .containsExactly(12L, 13L, 14L, 15L, 16L, 17L, 18L, 19L);
        assertThat(EveningSummaryService.compared(evenings.subList(0, 3), EVENING)).hasSize(3);
    }

    /** The evenings' rows carry what "Ostatnie wieczory" shows: the votes, the played, the rejected, the first and last request. */
    @Test
    void theEvenings_carryWhatTheComparisonShows() {
        SongRequestRepository repository = mock(SongRequestRepository.class);
        when(repository.findEvenings("SUMM1", EveningSummaryService.MAX_EVENINGS)).thenReturn(List.<Object[]>of(
                new Object[]{"2026-10-03", 42L, 30L, 5L, java.sql.Timestamp.from(at("19:05")), java.sql.Timestamp.from(at("02:40"))}));

        assertThat(new EveningSummaryService(repository, mock(DjLibraryService.class), mock(com.scan2play.repository.EveningSummaryRepository.class))
                .evenings("SUMM1")).containsExactly(new EveningSummaryService.Evening(EVENING, 42, 30, 5, at("19:05"), at("02:40")));
    }

    /** The evenings by month for the page's list, the latest first, as the query gives them. */
    @Test
    void theEvenings_byMonth() {
        List<EveningSummaryService.Month> months = EveningSummaryService.byMonth(List.of(
                new EveningSummaryService.Evening(LocalDate.of(2026, 11, 4), 3), new EveningSummaryService.Evening(LocalDate.of(2026, 10, 31), 13),
                new EveningSummaryService.Evening(LocalDate.of(2026, 10, 3), 8)));

        assertThat(months).extracting(EveningSummaryService.Month::first).containsExactly(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 10, 1));
        assertThat(months.get(1).evenings()).hasSize(2);
        assertThat(EveningSummaryService.byMonth(List.of())).isEmpty();
    }

    /** The DJ's title of an evening: one line, at most 60 characters (an emoji one), empty none. */
    @Test
    void theTitle_isOneLine_atMostSixtyCharacters() {
        assertThat(EveningSummaryService.tidyTitle("  Wesele\n Ani   i Tomka ")).isEqualTo("Wesele Ani i Tomka");
        assertThat(EveningSummaryService.tidyTitle("   ")).isNull();
        assertThat(EveningSummaryService.tidyTitle(null)).isNull();
        assertThat(EveningSummaryService.tidyTitle("💍".repeat(70))).isEqualTo("💍".repeat(60));
        assertThat(EveningSummaryService.tidyTitle("a".repeat(61))).hasSize(60);
    }

    /** A hosts' link opens while it works: an unknown, too long, old or switched-off one does not. */
    @Test
    void aHostsLink_opensWhileItWorks() {
        com.scan2play.repository.EveningSummaryRepository kept = mock(com.scan2play.repository.EveningSummaryRepository.class);
        com.scan2play.entity.EveningSummaryEntity works = com.scan2play.entity.EveningSummaryEntity.builder().partyCode("SUMM1").evening(EVENING)
                .shareToken("good").shareExpiresAt(Instant.now().plusSeconds(60)).build();
        when(kept.findByShareToken("good")).thenReturn(java.util.Optional.of(works));
        when(kept.findByShareToken("old")).thenReturn(java.util.Optional.of(com.scan2play.entity.EveningSummaryEntity.builder().partyCode("SUMM1")
                .evening(EVENING).shareToken("old").shareExpiresAt(Instant.now().minusSeconds(1)).build()));
        EveningSummaryService service = new EveningSummaryService(mock(SongRequestRepository.class), mock(DjLibraryService.class), kept);

        assertThat(service.shared("good")).contains(works);
        assertThat(service.shared("old")).isEmpty();
        assertThat(service.shared("unknown")).isEmpty();
        assertThat(service.shared("x".repeat(33))).isEmpty();
        assertThat(service.shared(null)).isEmpty();
        // a new link: 22 characters of base64url, 30 days
        String token = service.share("SUMM1", EVENING);
        assertThat(token).matches("[A-Za-z0-9_-]{22}");
        verify(kept).saveShare(eq("SUMM1"), eq(EVENING), eq(token), any(), any());
    }

    @Test
    void theEvenings_comeFromTheQuery_latestFirst() {
        SongRequestRepository repository = mock(SongRequestRepository.class);
        when(repository.findEvenings("SUMM1", EveningSummaryService.MAX_EVENINGS))
                .thenReturn(List.of(new Object[]{"2026-10-03", 42L}, new Object[]{"2026-09-26", 7L}));

        assertThat(new EveningSummaryService(repository, org.mockito.Mockito.mock(DjLibraryService.class), mock(com.scan2play.repository.EveningSummaryRepository.class)).evenings("SUMM1")).containsExactly(
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
        requests.get(1).setDedications(2);
        requests.get(3).setDedications(5);   // rejected by the AI: never counted

        EveningSummaryService.Summary summary = EveningSummaryService.summarize(EVENING, requests);

        assertThat(summary.requests()).isEqualTo(6);
        assertThat(summary.songs()).as("the songs the AI let through").isEqualTo(5);
        assertThat(summary.dedications()).isEqualTo(2);
        assertThat(summary.firstWanted().getSongName()).isEqualTo("Played early");
        assertThat(summary.lastPlayed().getSongName()).isEqualTo("Played late");
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
        assertThat(summary.top()).extracting(EveningSummaryService.Song::name)
                .containsExactly("Played late", "Played early", "Skipped", "Cleared", "Waiting");
        // wanted, not heard: what the AI let through and did not play
        assertThat(summary.missed()).extracting(EveningSummaryService.Song::name).containsExactly("Skipped", "Cleared", "Waiting");
        assertThat(summary.missed()).extracting(EveningSummaryService.Song::status).containsExactly(
                EveningSummaryService.Status.SKIPPED, EveningSummaryService.Status.CLEARED, EveningSummaryService.Status.WAITING);
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
        assertThat(lines[1]).endsWith(";;;30;\"Wilki\";\"Baśka\";0;");
        assertThat(lines[2]).endsWith(";\"tak\";;10;\"Kult\";\"Arahja\";0;");
    }

    /** A request sent at the venue's kiosk (V56): "tak" in the CSV's column "Z kiosku", after "Dodana przez DJ-a". */
    @Test
    void theCsv_saysWhichRequestsCameFromTheKiosk() {
        ResourceBundleMessageSource messages = new ResourceBundleMessageSource();
        messages.setBasename("messages");
        messages.setDefaultEncoding("UTF-8");
        messages.setFallbackToSystemLocale(false);
        SongRequestEntity kiosk = request("Kult - Arahja", "played", "21:00", "21:10", 1);
        kiosk.setViaKiosk(true);

        String[] lines = EveningSummaryService.toCsv(List.of(request("Wilki - Baśka", "played", "20:00", "20:30", 2), kiosk), messages,
                Locale.forLanguageTag("pl")).split("\r\n");

        assertThat(lines[0]).endsWith(";Dodana przez DJ-a;Z kiosku;Czekała (min);Wykonawca;Tytuł;Dedykacje;Powód odrzucenia");
        assertThat(lines[1]).endsWith(";;;30;\"Wilki\";\"Baśka\";0;");
        assertThat(lines[2]).endsWith(";;\"tak\";10;\"Kult\";\"Arahja\";0;");
        assertThat(EveningSummaryService.toCsv(List.of(kiosk), messages, Locale.ENGLISH).split("\r\n")[0])
                .endsWith(";Added by the DJ;From the kiosk;Waited (min);Artist;Title;Dedications;Why the AI rejected it");
    }

    @Test
    void theTopSongs_areTen_theMostVotesFirst_theEarlierAmongEqualOnes() {
        List<SongRequestEntity> requests = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            requests.add(request("Song " + i, "accepted", "%02d:%02d".formatted(20 + i / 6, (i % 6) * 10), null, i == 7 ? 9 : 1));
        }

        assertThat(EveningSummaryService.summarize(EVENING, requests).top()).extracting(EveningSummaryService.Song::name)
                .hasSize(10).startsWith("Song 7", "Song 0", "Song 1");
    }

    /**
     * A song asked for again — after it played (out of the AI's duplicate window), after the queue was cleared, two hours after a
     * skip — is a row of its own. The lists count it once: one line of the most wanted with the votes of both, never "wanted, not
     * heard" when one of them played, one song of its artist; and "played" says how often.
     */
    @Test
    void aSongAskedForAgain_isOneSong() {
        SongRequestEntity clearedAgain = request("Zenek Martyniuk - Przekorny los", "rejected", "00:30", null, 4);
        clearedAgain.setClearedAt(at("01:00"));
        SongRequestEntity skipped = request("Daria Zawiałow - Kinga", "rejected", "21:00", null, 2);
        skipped.setSkippedAt(at("21:10"));
        List<SongRequestEntity> requests = List.of(
                request("Zenek Martyniuk - Przekorny los", "played", "20:00", "20:30", 6),
                request("ABBA - Dancing Queen", "played", "21:00", "21:10", 2),
                skipped,
                request("Daria Zawiałow - Kinga", "accepted", "23:30", null, 1),
                request("Sanah - Szampan", "played", "22:00", "22:10", 3),
                request("sanah – Szampan", "played", "01:00", "01:20", 1),   // an older name, with "–": the same song
                request("Sanah - Ale jazz!", "accepted", "23:00", null, 1),
                clearedAgain);

        EveningSummaryService.Summary summary = EveningSummaryService.summarize(EVENING, requests);

        assertThat(summary.songs()).isEqualTo(5);
        assertThat(summary.top()).extracting(EveningSummaryService.Song::name, EveningSummaryService.Song::votes,
                EveningSummaryService.Song::timesPlayed).containsExactly(
                org.assertj.core.groups.Tuple.tuple("Zenek Martyniuk - Przekorny los", 10, 1),
                org.assertj.core.groups.Tuple.tuple("Sanah - Szampan", 4, 2),
                org.assertj.core.groups.Tuple.tuple("Daria Zawiałow - Kinga", 3, 0),
                org.assertj.core.groups.Tuple.tuple("ABBA - Dancing Queen", 2, 1),
                org.assertj.core.groups.Tuple.tuple("Sanah - Ale jazz!", 1, 0));
        assertThat(summary.top().getFirst().played()).isTrue();
        // the skipped song asked for again waits: waiting is its best end yet
        assertThat(summary.missed()).extracting(EveningSummaryService.Song::name, EveningSummaryService.Song::status).containsExactly(
                org.assertj.core.groups.Tuple.tuple("Daria Zawiałow - Kinga", EveningSummaryService.Status.WAITING),
                org.assertj.core.groups.Tuple.tuple("Sanah - Ale jazz!", EveningSummaryService.Status.WAITING));
        // the artists of two songs or more: Sanah's two (Szampan once), not Zenek's one
        assertThat(summary.artists()).containsExactly(new EveningSummaryService.ArtistCount("Sanah", 2, 5));
        // the setlist keeps every time a song played
        assertThat(summary.setlist()).hasSize(4);
    }

    /**
     * The night the clocks go back (25.10.2026, 03:00 → 02:00): the hour from 02:00 is there twice, so are its half-hours — two
     * requests at 02:15 an hour apart are two bars, not one counted twice.
     */
    @Test
    void theHalfHours_theNightTheClocksGoBack_countTheHourTwice() {
        LocalDate night = LocalDate.of(2026, 10, 24);
        Instant first = ZonedDateTime.of(2026, 10, 25, 2, 15, 0, 0, Times.DISPLAY_ZONE).withEarlierOffsetAtOverlap().toInstant();
        Instant second = ZonedDateTime.of(2026, 10, 25, 2, 15, 0, 0, Times.DISPLAY_ZONE).withLaterOffsetAtOverlap().toInstant();
        List<SongRequestEntity> requests = List.of(
                SongRequestEntity.builder().partyCode("SUMM1").songName("A - a").decision("accepted").requestedAt(first).votes(1).build(),
                SongRequestEntity.builder().partyCode("SUMM1").songName("B - b").decision("accepted").requestedAt(second).votes(1).build());

        EveningSummaryService.Summary summary = EveningSummaryService.summarize(night, requests);

        assertThat(summary.slots()).extracting(EveningSummaryService.TimeSlot::label).containsExactly("02:00", "02:30", "02:00");
        assertThat(summary.slots()).extracting(EveningSummaryService.TimeSlot::requests).containsExactly(1L, 0L, 1L);
        assertThat(summary.busiestSlot()).isEqualTo("02:00–02:30");
    }

    /** The bars of the half-hours: the busiest one the full height, a quiet one none, one request at least a sliver. */
    @Test
    void theBars_areMeasuredByTheBusiestHalfHour() {
        List<SongRequestEntity> requests = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            requests.add(request("Song " + i, "accepted", "22:0" + (i % 10), null, 1));
        }
        requests.add(request("Late", "accepted", "23:10", null, 1));

        EveningSummaryService.Summary summary = EveningSummaryService.summarize(EVENING, requests);

        assertThat(summary.slots()).extracting(summary::barHeight).containsExactly(EveningSummaryService.BAR_HEIGHT, 0, 3);
        assertThat(summary.isBusiest(summary.slots().getFirst())).isTrue();
        assertThat(summary.isBusiest(summary.slots().get(2))).isFalse();
        assertThat(summary.busiestSlotStart()).isEqualTo("22:00");
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
    void theArtists_ofWhatTheAiLetThrough_ofTwoSongsOrMore() {
        List<SongRequestEntity> requests = List.of(
                request("Eminem - Lose Yourself", "played", "20:00", "20:10", 1),
                request("eminem – Without Me", "accepted", "20:05", null, 1),   // an older name, with "–"
                request("Sanah - Szampan", "played", "20:10", "20:20", 2),
                request("Wilki - Baśka", "accepted", "20:15", null, 1),
                request("Nirvana - Smells Like Teen Spirit", "rejected", "20:20", null, 5),
                request("no artist here", "accepted", "20:25", null, 9));

        // an artist of one song says no more than the song: only those of two or more
        assertThat(EveningSummaryService.summarize(EVENING, requests).artists()).containsExactly(
                new EveningSummaryService.ArtistCount("Eminem", 2, 2));
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
        formula.setRejectReason(com.scan2play.model.RejectReason.OFF_VIBE);

        String csv = EveningSummaryService.toCsv(List.of(played, formula), messages, Locale.forLanguageTag("pl"));

        String[] lines = csv.split("\r\n");
        assertThat(lines[0]).isEqualTo((char) 0xFEFF + "Numer;Prośba;Zagrana;Piosenka;Gość napisał;Głosy;Napiwki;Status;Komentarz AI;"
                + "Dodana przez DJ-a;Z kiosku;Czekała (min);Wykonawca;Tytuł;Dedykacje;Powód odrzucenia");
        // after the columns of before: the minutes it waited, the artist and the title apart, the dedications counted
        assertThat(lines[1]).isEqualTo("27;\"2026-10-03 21:00\";\"2026-10-03 21:30\";\"Varius Manx - Orła cień\";"
                + "\"orła cień \"\"ten\"\"\";4;2;\"zagrana\";\"Klasyk!\";;;30;\"Varius Manx\";\"Orła cień\";0;");
        // a request the AI rejected has no number, waited for nothing; a name without " - " has no artist, its title a formula kept as text
        assertThat(lines[2]).isEqualTo(";\"2026-10-03 22:00\";;\"'=HYPERLINK(\"\"http://x\"\")\";\"'@SUM(1;2)\";1;0;"
                + "\"odrzucona przez AI\";;;;;;\"'=HYPERLINK(\"\"http://x\"\")\";0;\"nie pasowała do klimatu\"");
        assertThat(lines).hasSize(3);
    }

    /** "Kopiuj listę": the library's gaps one a line, as asked for. */
    @Test
    void theListToCopy_isASongALine() {
        assertThat(EveningSummaryService.copyList(List.of(
                new EveningSummaryService.LibraryGap("Wilki - Baśka", 5, 2, com.scan2play.model.LibraryMark.MISSING),
                new EveningSummaryService.LibraryGap("Dżem - Baśka", 1, 1, com.scan2play.model.LibraryMark.SIMILAR))))
                .isEqualTo("Wilki - Baśka\nDżem - Baśka");
        assertThat(EveningSummaryService.copyList(List.of())).isEmpty();
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

        assertThat(lines[0]).endsWith(";Komentarz AI;Dodana przez DJ-a;Z kiosku;Czekała (min);Wykonawca;Tytuł;Dedykacje;Powód odrzucenia;W bibliotece");
        assertThat(lines[1]).endsWith(";\"tak\"");
        assertThat(lines[2]).endsWith(";\"podobny\"");
        assertThat(lines[3]).endsWith(";\"nie\"");
    }
}
