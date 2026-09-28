package com.scan2play.entity;

import com.scan2play.model.FallbackTrackStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * One track of a party's fallback ("background music") playlist, stored server-side so the backend —
 * not the browser — decides what plays when the guest queue is empty (Section 14, Phase 2).
 * <p>
 * The playlist is fetched from the YouTube Data API once, when the DJ sets it, and stored as one row
 * per video. Changing the playlist soft-invalidates still-{@link FallbackTrackStatus#QUEUED} rows
 * ({@link FallbackTrackStatus#CANCELLED}) instead of deleting them; played rows stay as history.
 * <p>
 * <b>YouTube API ToS compliance:</b> only video IDs are stored, and rows older than
 * {@value #MAX_AGE_DAYS} days are purged (same rule as {@link YoutubeCacheEntity}).
 * The schema is created by Flyway migration {@code V2__create_fallback_track.sql}.
 */
@Entity
@Table(name = "fallback_track", indexes = {
        @Index(name = "idx_fallback_track_party_status", columnList = "partyCode, status")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FallbackTrackEntity {

    /** Maximum retention in days, per YouTube API Terms of Service. */
    public static final int MAX_AGE_DAYS = 30;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 5)
    private String partyCode;

    /** Source of the track: a playlist ID, or {@code V:<videoId>} for a single video. */
    @Column(nullable = false, length = 64)
    private String playlistId;

    /** The 11-character YouTube video ID. */
    @Column(nullable = false, length = 20)
    private String videoId;

    /** 0-based order of the track within its source playlist. */
    @Column(nullable = false)
    private int playlistPosition;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private FallbackTrackStatus status;

    /** When the track was fetched from the YouTube API (basis of the {@value #MAX_AGE_DAYS}-day retention). */
    @Column(nullable = false)
    private LocalDateTime fetchedAt;

    private LocalDateTime playedAt;
}
