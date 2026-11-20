package com.scan2play.service;

import com.scan2play.service.SongTrendsService.Result;
import com.scan2play.service.SongTrendsService.Row;
import com.scan2play.service.SongTrendsService.Trend;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** "Trendy próśb": the rows counted by song — parties, not rows; the thresholds; the name; the direction; the order. */
class SongTrendsServiceTest {

    private static Row row(String party, String song, boolean recent, boolean earlier) {
        return new Row(party, song, recent, earlier);
    }

    @Test
    void aSong_isCountedByParties_underItsComparableName_writtenAsMostPartiesWroteIt() {
        Result result = SongTrendsService.count(List.of(
                row("P1", "Wilki - Baśka", true, false),
                row("P2", "Wilki - Baśka", true, false),
                row("P3", "WILKI – BAŚKA", false, true),
                row("P3", "Wilki - Baska", false, true),
                row("P4", "Ktoś - Coś", true, false),
                row("P5", "Ktoś - Coś", true, false)), false);

        assertThat(result.parties()).isEqualTo(5);
        assertThat(result.songs()).as("\"Ktoś - Coś\" is on two parties only").isEqualTo(1);
        assertThat(result.belowMinimum()).isTrue();
        assertThat(result.top()).containsExactly(new Trend("Wilki - Baśka", 3, 2, 1, "up"));
    }

    @Test
    void theDirection_comparesTheTwoHalves() {
        List<Row> rows = new ArrayList<>();
        for (String party : List.of("A", "B", "C")) {
            rows.add(row(party, "New - Song", true, false));
            rows.add(row(party, "Steady - Song", party.equals("A"), !party.equals("A")));
            rows.add(row(party, "Old - Song", false, true));
        }
        rows.add(row("D", "Steady - Song", true, false));

        assertThat(SongTrendsService.count(rows, false).top()).extracting(Trend::song, Trend::direction).containsExactly(
                org.assertj.core.groups.Tuple.tuple("Steady - Song", "flat"),
                org.assertj.core.groups.Tuple.tuple("New - Song", "new"),
                org.assertj.core.groups.Tuple.tuple("Old - Song", "down"));
    }

    @Test
    void theTop_isTwentySongs_mostPartiesFirst_andTenPartiesAreEnough() {
        List<Row> rows = new ArrayList<>();
        for (int song = 0; song < 25; song++) {
            for (int party = 0; party < 3 + song % 7; party++) {
                rows.add(row("P" + party, "Artist " + song + " - Title", true, false));
            }
        }
        for (int party = 0; party < 10; party++) {
            rows.add(row("P" + party, "Everyone - Hit", false, true));
        }

        Result result = SongTrendsService.count(rows, true);

        assertThat(result.parties()).isEqualTo(10);
        assertThat(result.belowMinimum()).isFalse();
        assertThat(result.truncated()).isTrue();
        assertThat(result.songs()).isEqualTo(26);
        assertThat(result.top()).hasSize(SongTrendsService.TOP);
        assertThat(result.top().getFirst()).isEqualTo(new Trend("Everyone - Hit", 10, 0, 10, "down"));
        assertThat(result.top()).extracting(Trend::parties).isSortedAccordingTo(java.util.Comparator.reverseOrder());
    }

    @Test
    void aNameOfNothingButPunctuation_isLeftOut() {
        Result result = SongTrendsService.count(List.of(row("P1", " - ", true, false), row("P2", "–", true, false),
                row("P3", "", true, false)), false);

        assertThat(result.parties()).isZero();
        assertThat(result.top()).isEmpty();
    }
}
