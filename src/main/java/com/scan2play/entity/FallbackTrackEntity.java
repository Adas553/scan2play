package com.scan2play.entity;

import com.scan2play.model.FallbackTrackStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * One track of a party's fallback ("background music") playlist, stored server-side so the backend —
 * not the browser — decides what plays when the guest queue is empty (Section 14, Phase 2).
 * <p>
 * The playlist is fetched from the YouTube Data API once, when the DJ sets it, and stored as one row
 * per video. Changing the playlist soft-invalidates still-{@link FallbackTrackStatus#QUEUED} rows
 * ({@link FallbackTrackStatus#CANCELLED}) instead of deleting them. A row records only the <em>current round</em> of its
 * playlist: when the playlist loops, the played rows go back in the queue and lose their {@link #playedAt}. What has
 * played over the whole party — the DJ history — is kept in {@link FallbackPlayEntity} (V7).
 * <p>
 * <b>YouTube API ToS compliance:</b> only the video ID and its title are stored, and rows older than
 * {@value #MAX_AGE_DAYS} days are purged (same rule as {@link YoutubeCacheEntity}).
 * The schema is created by Flyway migrations {@code V2__create_fallback_track.sql} and
 * {@code V4__fallback_track_order_and_title.sql} and {@code V5__fallback_track_manual_move.sql}; its indexes by {@code V9}.
 */
@Entity
@Table(name = "fallback_track", indexes = {
        @Index(name = "idx_fallback_track_queue", columnList = "partyCode, playlistId, status, playOrder, playlistPosition"),
        @Index(name = "idx_fallback_track_party_fetched", columnList = "partyCode, fetchedAt")
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

    /**
     * When the track plays: among the QUEUED tracks of a playlist the lowest value goes first (ties are broken by
     * {@link #playlistPosition}). Playlist order, a random order (shuffle) or — for a playlist that was already
     * partly played — playlist order continuing after the last played track; see {@code FallbackTrackCommandService}.
     */
    @Column(nullable = false)
    private int playOrder;

    /**
     * True while the DJ has moved this track by hand within the current order (V5). Cleared whenever the queued
     * tracks are given a new order (import, shuffle, a new round, the shuffle switch).
     */
    @Column(nullable = false)
    private boolean manualMove;

    /** Video title shown to the DJ; {@code null} when unknown (rows imported before V4, or a failed lookup). */
    @Column(length = 255)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private FallbackTrackStatus status;

    /** When the track was fetched from the YouTube API (basis of the {@value #MAX_AGE_DAYS}-day retention). */
    @Column(nullable = false)
    private Instant fetchedAt;

    /** When the player took the track in the current round; cleared when the playlist starts a new round. */
    private Instant playedAt;
}
