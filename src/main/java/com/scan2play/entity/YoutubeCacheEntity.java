package com.scan2play.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Persistent cache for YouTube video ID lookups (L2 cache layer).
 * <p>
 * Each YouTube Data API v3 search costs 100 quota units (daily limit: 10,000).
 * This table stores resolved video IDs so that the same song doesn't cost quota
 * repeatedly — even across application restarts or different parties.
 * <p>
 * <b>YouTube API ToS compliance:</b> Cached data expires after {@value #MAX_AGE_DAYS} days.
 * Expired entries are refreshed on next access and periodically cleaned up.
 * <pre>
 * Request → [L1: Caffeine in-memory] → [L2: this table] → [YouTube API, 100 quota]
 * </pre>
 */
@Entity
@Table(name = "youtube_cache", indexes = {
        @Index(name = "idx_youtube_cache_query", columnList = "searchQuery", unique = true)
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class YoutubeCacheEntity {

    /** Maximum cache age in days, per YouTube API Terms of Service. */
    public static final int MAX_AGE_DAYS = 30;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Normalized search query (lowercase, trimmed). Used as the unique lookup key. */
    @Column(nullable = false, unique = true, length = 500)
    private String searchQuery;

    /** The 11-character YouTube video ID (e.g., "dQw4w9WgXcQ"). */
    @Column(nullable = false, length = 20)
    private String videoId;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    /**
     * Returns {@code true} if this cache entry is older than {@value #MAX_AGE_DAYS} days
     * and must be refreshed via a new YouTube API call.
     */
    public boolean isExpired() {
        return createdAt.plusDays(MAX_AGE_DAYS).isBefore(LocalDateTime.now());
    }
}

