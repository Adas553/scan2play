package com.scan2play.controller;

import com.scan2play.service.EveningSummaryService;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Which evening the summary shows (the page itself: {@code SummaryPageTest}). */
class DjSummaryControllerTest {

    private static final LocalDate EVENING = LocalDate.of(2026, 10, 3);
    private static final List<EveningSummaryService.Evening> TWO_EVENINGS = List.of(
            new EveningSummaryService.Evening(EVENING, 8), new EveningSummaryService.Evening(LocalDate.of(2026, 9, 26), 3));

    @Test
    void theEveningAskedFor_orTheLatest() {
        assertThat(DjSummaryController.pick("2026-09-26", TWO_EVENINGS)).isEqualTo(LocalDate.of(2026, 9, 26));
        assertThat(DjSummaryController.pick(" 2026-09-26 ", TWO_EVENINGS)).isEqualTo(LocalDate.of(2026, 9, 26));
        assertThat(DjSummaryController.pick("not a date", TWO_EVENINGS)).isEqualTo(EVENING);
        assertThat(DjSummaryController.pick(null, TWO_EVENINGS)).isEqualTo(EVENING);
        assertThat(DjSummaryController.pick(null, List.of())).isNull();
    }
}
