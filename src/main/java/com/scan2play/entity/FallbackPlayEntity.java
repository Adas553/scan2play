package com.scan2play.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * One time the player took a track from a party's background playlist — the log the DJ history and the "previous
 * track" button read (Flyway {@code V7__fallback_play_log.sql}).
 * <p>
 * It exists because {@link FallbackTrackEntity} cannot tell the history: when a playlist starts a new round its
 * played tracks go back in the queue and lose their {@code playedAt}. A log row is written when a track is handed out
 * (in the same transaction, see {@code FallbackTrackCommandService#takeNextTrack}) and never changes; it is not
 * touched by rounds, by the queue or by the DJ replacing the playlist.
 * <p>
 * <b>The id is the identity of the play</b>, not of the track: the dashboard knows what it plays as {@code B:<id>}
 * ({@code HistoryEntry#key()}), and the same video playing in two rounds must be two different entries — with one key
 * for both, "back" from the older one would find the newer one and go round in circles.
 * <p>
 * <b>YouTube API ToS compliance:</b> the video ID and title are API data, so a row is deleted
 * {@value FallbackTrackEntity#MAX_AGE_DAYS} days after the track was fetched ({@link #fetchedAt}, copied from the
 * track), together with the tracks themselves.
 */
@Entity
@Table(name = "fallback_play", indexes = {
        @Index(name = "idx_fallback_play_party_played", columnList = "partyCode, playedAt DESC, id DESC")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FallbackPlayEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 5)
    private String partyCode;

    /** The 11-character YouTube video ID. */
    @Column(nullable = false, length = 20)
    private String videoId;

    /** The title as it was when the playlist was imported; {@code null} when it was unknown. */
    @Column(length = 255)
    private String title;

    /** When the track was fetched from the YouTube API — the basis of the 30-day retention. */
    @Column(nullable = false)
    private LocalDateTime fetchedAt;

    /** When the player took the track. */
    @Column(nullable = false)
    private LocalDateTime playedAt;
}
