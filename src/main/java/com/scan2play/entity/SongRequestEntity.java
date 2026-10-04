package com.scan2play.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

@Entity
@Table(name = "song_requests", indexes = {
    @Index(name = "idx_party_decision_time", columnList = "partyCode, decision, requestedAt DESC")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SongRequestEntity {

    /**
     * How long a request is kept, counted from {@link #requestedAt}: a request older than this many days is deleted
     * (nightly, see {@code SongRequestRetentionService}); the privacy pages say the same.
     */
    public static final int MAX_AGE_DAYS = 30;

    private static final int DJ_COMMENT_MAX = 500;
    private static final int SONG_NAME_MAX = 255;
    private static final int TRACK_URL_MAX = 500;
    private static final int GUEST_TEXT_MAX = 150;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 5)
    private String partyCode;

    private String songName;

    /**
     * What the guest typed, as typed (one line, at most 150 characters — what the AI is given; V14), so that the DJ can check the
     * song the AI made of it. {@code null} for a DJ's pick and for requests from before V14.
     */
    @Column(length = GUEST_TEXT_MAX)
    private String guestText;

    /**
     * How many guests asked for this song (V15): a request for a song that already waits in the queue adds a vote here instead of
     * a row of its own ({@code SongRequestCommandService}). 1 for a new request and for a DJ's pick.
     */
    @Column(nullable = false)
    @Builder.Default
    private int votes = 1;

    private String style;
    private String decision;

    @Column(length = DJ_COMMENT_MAX)
    private String djComment;

    private int energyLevel;
    private Instant requestedAt;

    @Column(length = TRACK_URL_MAX)
    private String trackUrl;

    /**
     * When the request became "played" (V6); {@code null} until then, for rejected requests, and for requests that
     * were played before V6 (the history then falls back to {@link #requestedAt}).
     */
    private Instant playedAt;

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
        guestText = truncate(guestText, GUEST_TEXT_MAX);
    }

    private static String truncate(String value, int maxLength) {
        return (value != null && value.length() > maxLength)
                ? value.substring(0, maxLength)
                : value;
    }
}
