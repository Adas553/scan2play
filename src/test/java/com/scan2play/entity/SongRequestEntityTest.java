package com.scan2play.entity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link SongRequestEntity} defensive truncation logic.
 * This is a critical safety net preventing DataIntegrityViolationException
 * from AI-generated content exceeding column limits.
 */
class SongRequestEntityTest {

    @Test
    void truncateFields_shouldTruncateDjCommentExceeding500Chars() {
        SongRequestEntity entity = SongRequestEntity.builder()
                .partyCode("ABC12")
                .djComment("x".repeat(600))
                .build();

        entity.truncateFields();

        assertThat(entity.getDjComment()).hasSize(500);
    }

    @Test
    void truncateFields_shouldTruncateSongNameExceeding255Chars() {
        SongRequestEntity entity = SongRequestEntity.builder()
                .partyCode("ABC12")
                .songName("a".repeat(300))
                .build();

        entity.truncateFields();

        assertThat(entity.getSongName()).hasSize(255);
    }

    @Test
    void truncateFields_shouldTruncateTrackUrlExceeding500Chars() {
        SongRequestEntity entity = SongRequestEntity.builder()
                .partyCode("ABC12")
                .trackUrl("https://example.com/" + "z".repeat(600))
                .build();

        entity.truncateFields();

        assertThat(entity.getTrackUrl()).hasSize(500);
    }

    @Test
    void truncateFields_shouldNotTruncateFieldsWithinLimits() {
        SongRequestEntity entity = SongRequestEntity.builder()
                .partyCode("ABC12")
                .songName("Nirvana - Smells Like Teen Spirit")
                .djComment("Great pick!")
                .trackUrl("https://youtube.com/watch?v=abc123")
                .build();

        entity.truncateFields();

        assertThat(entity.getSongName()).isEqualTo("Nirvana - Smells Like Teen Spirit");
        assertThat(entity.getDjComment()).isEqualTo("Great pick!");
        assertThat(entity.getTrackUrl()).isEqualTo("https://youtube.com/watch?v=abc123");
    }

    @Test
    void truncateFields_shouldHandleNullFieldsGracefully() {
        SongRequestEntity entity = SongRequestEntity.builder()
                .partyCode("ABC12")
                .songName(null)
                .djComment(null)
                .trackUrl(null)
                .build();

        entity.truncateFields();

        assertThat(entity.getSongName()).isNull();
        assertThat(entity.getDjComment()).isNull();
        assertThat(entity.getTrackUrl()).isNull();
    }

    @Test
    void truncateFields_shouldPreserveFieldsAtExactLimit() {
        String exactSongName = "a".repeat(255);
        String exactComment = "b".repeat(500);
        String exactUrl = "c".repeat(500);

        SongRequestEntity entity = SongRequestEntity.builder()
                .partyCode("ABC12")
                .songName(exactSongName)
                .djComment(exactComment)
                .trackUrl(exactUrl)
                .build();

        entity.truncateFields();

        assertThat(entity.getSongName()).hasSize(255);
        assertThat(entity.getDjComment()).hasSize(500);
        assertThat(entity.getTrackUrl()).hasSize(500);
    }
}

