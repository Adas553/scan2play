package com.scan2play.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "song_requests", indexes = {
    @Index(name = "idx_party_code", columnList = "partyCode"),
    @Index(name = "idx_party_decision_time", columnList = "partyCode, decision, requestedAt DESC")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SongRequestEntity {

    private static final int DJ_COMMENT_MAX = 500;
    private static final int SONG_NAME_MAX = 255;
    private static final int TRACK_URL_MAX = 500;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 5)
    private String partyCode;

    private String songName;
    private String style;
    private String decision;

    @Column(length = DJ_COMMENT_MAX)
    private String djComment;

    private int energyLevel;
    private LocalDateTime requestedAt;

    @Column(length = TRACK_URL_MAX)
    private String trackUrl;

    /**
     * When the request became "played" (V6); {@code null} until then, for rejected requests, and for requests that
     * were played before V6 (the history then falls back to {@link #requestedAt}).
     */
    private LocalDateTime playedAt;

    /**
     * Defensive truncation of all free-text fields before persist/update.
     * Prevents DataIntegrityViolationException from AI-generated content
     * that may exceed column limits.
     */
    @PrePersist
    @PreUpdate
    void truncateFields() {
        songName = truncate(songName, SONG_NAME_MAX);
        djComment = truncate(djComment, DJ_COMMENT_MAX);
        trackUrl = truncate(trackUrl, TRACK_URL_MAX);
    }

    private static String truncate(String value, int maxLength) {
        return (value != null && value.length() > maxLength)
                ? value.substring(0, maxLength)
                : value;
    }
}
