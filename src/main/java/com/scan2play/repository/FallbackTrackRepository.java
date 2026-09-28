package com.scan2play.repository;

import com.scan2play.entity.FallbackTrackEntity;
import com.scan2play.model.FallbackTrackStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;

@Repository
public interface FallbackTrackRepository extends JpaRepository<FallbackTrackEntity, Long> {

    /**
     * Bulk status change for one party (e.g. QUEUED → CANCELLED when the playlist is replaced).
     *
     * @return number of updated rows
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE FallbackTrackEntity t SET t.status = :to WHERE t.partyCode = :partyCode AND t.status = :from")
    int updateStatus(@Param("partyCode") String partyCode,
                     @Param("from") FallbackTrackStatus from,
                     @Param("to") FallbackTrackStatus to);

    long countByPartyCodeAndStatus(String partyCode, FallbackTrackStatus status);

    /**
     * Deletes every fallback track of a party. Used during account deletion
     * (Google API Services User Data Policy).
     *
     * @return number of deleted rows
     */
    @Modifying
    @Query("DELETE FROM FallbackTrackEntity t WHERE t.partyCode = :partyCode")
    int deleteByPartyCode(@Param("partyCode") String partyCode);

    /**
     * Deletes tracks fetched before the cutoff — YouTube API data must not be retained longer than 30 days.
     *
     * @return number of deleted rows
     */
    @Modifying
    @Query("DELETE FROM FallbackTrackEntity t WHERE t.fetchedAt < :cutoff")
    int deleteFetchedBefore(@Param("cutoff") LocalDateTime cutoff);
}
