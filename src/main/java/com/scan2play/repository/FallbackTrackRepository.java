package com.scan2play.repository;

import com.scan2play.entity.FallbackTrackEntity;
import com.scan2play.model.FallbackTrackStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

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

    long countByPartyCodeAndPlaylistIdAndStatus(String partyCode, String playlistId, FallbackTrackStatus status);

    /**
     * One-row "page" used to pick a track by offset: the caller passes
     * {@code PageRequest.of(index, 1, Sort.by("playlistPosition"))}. Returns a plain list, so no count query runs.
     */
    List<FallbackTrackEntity> findByPartyCodeAndPlaylistIdAndStatus(String partyCode, String playlistId,
                                                                    FallbackTrackStatus status, Pageable pageable);

    /** When the newest import of this playlist for the party was fetched (empty if it was never imported). */
    @Query("SELECT MAX(t.fetchedAt) FROM FallbackTrackEntity t WHERE t.partyCode = :partyCode AND t.playlistId = :playlistId")
    Optional<LocalDateTime> findLatestFetchedAt(@Param("partyCode") String partyCode,
                                                @Param("playlistId") String playlistId);

    /**
     * Claims a queued track: QUEUED → PLAYED in a single conditional UPDATE, so two concurrent callers can
     * never both get the same track — the loser sees 0 updated rows.
     *
     * @return 1 if this caller claimed the track, 0 if it was no longer queued
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE FallbackTrackEntity t SET t.status = :played, t.playedAt = :now WHERE t.id = :id AND t.status = :queued")
    int claimQueuedTrack(@Param("id") Long id,
                         @Param("queued") FallbackTrackStatus queued,
                         @Param("played") FallbackTrackStatus played,
                         @Param("now") LocalDateTime now);

    /**
     * Puts the already-played tracks of the party's <em>newest</em> import back in the queue (the playlist
     * loops). "Newest" is party-wide, not per playlist: a batch that a later import — of the same or of
     * another playlist — has superseded is history and is never revived. If the newest import belongs to a
     * different playlist than {@code playlistId}, nothing matches and 0 is returned.
     *
     * @return number of tracks re-queued
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE FallbackTrackEntity t SET t.status = :queued, t.playedAt = null "
            + "WHERE t.partyCode = :partyCode AND t.playlistId = :playlistId AND t.status = :played "
            + "AND t.fetchedAt = (SELECT MAX(x.fetchedAt) FROM FallbackTrackEntity x WHERE x.partyCode = :partyCode)")
    int requeuePlayedTracks(@Param("partyCode") String partyCode,
                            @Param("playlistId") String playlistId,
                            @Param("played") FallbackTrackStatus played,
                            @Param("queued") FallbackTrackStatus queued);

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
