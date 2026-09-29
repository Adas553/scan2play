package com.scan2play.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PlaylistTrackTest {

    @Test
    @DisplayName("a normal title is kept, without surrounding whitespace")
    void shouldKeepTitle() {
        assertThat(new PlaylistTrack("dQw4w9WgXcQ", "  Never Gonna Give You Up ").title()).isEqualTo("Never Gonna Give You Up");
    }

    @Test
    @DisplayName("a missing or blank title becomes null (\"unknown\")")
    void shouldTreatBlankTitleAsUnknown() {
        assertThat(new PlaylistTrack("dQw4w9WgXcQ", null).title()).isNull();
        assertThat(new PlaylistTrack("dQw4w9WgXcQ", "").title()).isNull();
        assertThat(new PlaylistTrack("dQw4w9WgXcQ", "   ").title()).isNull();
    }

    @Test
    @DisplayName("a title longer than the database column is cut instead of failing the whole import")
    void shouldCutOverlongTitle() {
        String title = "x".repeat(PlaylistTrack.MAX_TITLE_LENGTH + 40);

        assertThat(new PlaylistTrack("dQw4w9WgXcQ", title).title()).hasSize(PlaylistTrack.MAX_TITLE_LENGTH);
    }
}
