package com.scan2play.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class TimesTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 3);

    @Test
    @DisplayName("the lists show the time in Polish time, and the day as today / yesterday / a date (with the year only in another year)")
    void shouldShowACompactMoment() {
        Instant evening = Instant.parse("2026-10-03T18:04:59Z");          // 20:04 in Warsaw (summer time)
        Instant lateYesterday = Instant.parse("2026-10-02T22:30:00Z");    // 00:30 on the 3rd in Warsaw — today, not yesterday
        Instant yesterday = Instant.parse("2026-10-02T21:59:00Z");        // 23:59 on the 2nd in Warsaw
        Instant lastYear = Instant.parse("2025-12-31T12:00:00Z");

        assertThat(Times.clock(evening)).isEqualTo("20:04");
        assertThat(Times.daysAgo(evening, TODAY)).isZero();
        assertThat(Times.daysAgo(lateYesterday, TODAY)).as("the day is Warsaw's, not UTC's").isZero();
        assertThat(Times.daysAgo(yesterday, TODAY)).isEqualTo(1);
        assertThat(Times.day(Instant.parse("2026-09-29T18:00:00Z"), TODAY)).isEqualTo("29.09");
        assertThat(Times.day(lastYear, TODAY)).isEqualTo("31.12.2025");
        assertThat(Times.display(evening)).as("the full moment stays, for the cell's title").isEqualTo("03.10.2026 20:04:59");
    }

    @Test
    @DisplayName("no moment: nothing shown")
    void shouldShowNothingForNoMoment() {
        assertThat(Times.clock(null)).isEmpty();
        assertThat(Times.day(null, TODAY)).isEmpty();
        assertThat(Times.daysAgo(null, TODAY)).isEqualTo(-1);
    }
}
