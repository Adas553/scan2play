package com.scan2play.repository;

import com.scan2play.entity.YoutubeCacheEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

@Repository
public interface YoutubeCacheRepository extends JpaRepository<YoutubeCacheEntity, Long> {

    /**
     * Finds a cached video ID by normalized search query.
     * Uses the unique index {@code idx_youtube_cache_query} for O(1) lookup.
     */
    Optional<YoutubeCacheEntity> findBySearchQuery(String searchQuery);

    /**
     * Deletes all cache entries older than the given cutoff date.
     * Used for periodic cleanup to comply with YouTube API Terms of Service
     * (cached data must not be retained longer than 30 days).
     *
     * @param cutoff entries with {@code createdAt} before this timestamp are deleted
     * @return number of deleted rows
     */
    @Modifying
    @Query("DELETE FROM YoutubeCacheEntity e WHERE e.createdAt < :cutoff")
    int deleteExpiredBefore(@Param("cutoff") LocalDateTime cutoff);
}

