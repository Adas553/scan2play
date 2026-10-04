package com.scan2play.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The history's filter buttons: what each one reads, and how a URL parameter becomes one.
 */
class HistoryFilterTest {

    @Test
    @DisplayName("each filter reads exactly the requests its button promises")
    void shouldSayWhatEachFilterReads() {
        assertThat(kinds(HistoryFilter.ALL)).containsExactly(true, true);
        assertThat(kinds(HistoryFilter.PLAYED)).containsExactly(true, false);
        assertThat(kinds(HistoryFilter.REJECTED)).containsExactly(false, true);
    }

    /** played, rejected */
    private static Boolean[] kinds(HistoryFilter filter) {
        return new Boolean[] {filter.includesPlayed(), filter.includesRejected()};
    }

    @Test
    @DisplayName("the URL value is the lower-case name (what the buttons carry), and reading it back gives the same filter")
    void shouldRoundTripThroughTheUrlValue() {
        for (HistoryFilter filter : HistoryFilter.values()) {
            assertThat(filter.param()).isEqualTo(filter.name().toLowerCase());
            assertThat(HistoryFilter.fromParam(filter.param())).isEqualTo(filter);
        }
    }

    @Test
    @DisplayName("a missing, empty, unknown or old value (\"guest\", \"background\") is All; case and surrounding blanks do not matter")
    void shouldFallBackToAll() {
        assertThat(HistoryFilter.fromParam(null)).isEqualTo(HistoryFilter.ALL);
        assertThat(HistoryFilter.fromParam("")).isEqualTo(HistoryFilter.ALL);
        assertThat(HistoryFilter.fromParam("everything")).isEqualTo(HistoryFilter.ALL);
        assertThat(HistoryFilter.fromParam("guest")).isEqualTo(HistoryFilter.ALL);
        assertThat(HistoryFilter.fromParam("background")).isEqualTo(HistoryFilter.ALL);
        assertThat(HistoryFilter.fromParam("PLAYED")).isEqualTo(HistoryFilter.PLAYED);
        assertThat(HistoryFilter.fromParam("  rejected ")).isEqualTo(HistoryFilter.REJECTED);
    }
}
