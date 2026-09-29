package com.scan2play.repository;

import com.scan2play.entity.FallbackPlayEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface FallbackPlayRepository extends JpaRepository<FallbackPlayEntity, Long> {

    /**
     * A party's plays, the most recent first — for the DJ history and "previous track" ({@code PlayHistoryService}).
     * Ties (the same millisecond) go to the higher id, so the order is always the same. Bounded by the pageable, one
     * party; served by {@code idx_fallback_play_party_played}.
     */
    @Query("SELECT p FROM FallbackPlayEntity p WHERE p.partyCode = :partyCode ORDER BY p.playedAt DESC, p.id DESC")
    List<FallbackPlayEntity> findRecent(@Param("partyCode") String partyCode, Pageable pageable);

    /**
     * Deletes every play of a party. Used during account deletion (Google API Services User Data Policy).
     *
     * @return number of deleted rows
     */
    @Modifying
    @Query("DELETE FROM FallbackPlayEntity p WHERE p.partyCode = :partyCode")
    int deleteByPartyCode(@Param("partyCode") String partyCode);

    /**
     * Deletes plays whose track was fetched before the cutoff — YouTube API data must not be retained longer than
     * 30 days.
     *
     * @return number of deleted rows
     */
    @Modifying
    @Query("DELETE FROM FallbackPlayEntity p WHERE p.fetchedAt < :cutoff")
    int deleteFetchedBefore(@Param("cutoff") LocalDateTime cutoff);
}
