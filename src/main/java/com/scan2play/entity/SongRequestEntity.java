package com.scan2play.entity;

import com.scan2play.util.SongNames;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.DynamicUpdate;

import java.time.Instant;

/**
 * A guest's song request.
 * <p>
 * {@link DynamicUpdate}: an UPDATE writes only the columns that changed. The DJ's actions read a row, change its decision and save
 * it, while a guest's vote ({@code SongRequestRepository.addVote}) may commit in between — writing the whole row back would put
 * the votes as they were read and lose that vote ({@code SongRequestVotesIT}).
 */
@Entity
@DynamicUpdate
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
     * song the AI made of it. {@code null} for requests from before V14.
     */
    @Column(length = GUEST_TEXT_MAX)
    private String guestText;

    /**
     * How many guests asked for this song (V15): a request for a song that already waits in the queue adds a vote here instead of
     * a row of its own ({@code SongRequestCommandService}). 1 for a new request.
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
     * When the DJ skipped the request ("Pomiń", V20); {@code null} for every other request (one cleared with the whole queue too)
     * and once the DJ put it back. The same song stays out of the queue for a while from then ({@code SongRequestCommandService}).
     */
    private Instant skippedAt;

    /**
     * When the DJ cleared the request with the whole queue ("Wyczyść kolejkę", V25); {@code null} for every other request. The AI's
     * comment stays, the history says it was cleared. Unlike a skip, it keeps no song out of the queue and has no "↩ Przywróć".
     */
    private Instant clearedAt;

    /** Whether the AI's song has none of the guest's words in it: the queue marks it "⚠ Sprawdź" ({@code SongNames.sharesNoWord}). */
    public boolean needsCheck() {
        return SongNames.sharesNoWord(guestText, songName);
    }

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
