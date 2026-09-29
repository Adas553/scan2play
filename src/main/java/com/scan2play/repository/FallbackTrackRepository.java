package com.scan2play.repository;

import com.scan2play.entity.FallbackTrackEntity;
import com.scan2play.model.FallbackTrackStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
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

    /** The order in which QUEUED tracks play: lowest {@code playOrder} first, ties by playlist position. */
    Sort UPCOMING_ORDER = Sort.by("playOrder", "playlistPosition");

    /**
     * A page of tracks with the given status; for the queue the caller passes
     * {@code PageRequest.of(0, n, UPCOMING_ORDER)} to get the next {@code n} tracks. Returns a plain list, so no
     * count query runs.
     */
    List<FallbackTrackEntity> findByPartyCodeAndPlaylistIdAndStatus(String partyCode, String playlistId,
                                                                    FallbackTrackStatus status, Pageable pageable);

    /** The track of this playlist that was handed out last (the one playing now, or the last one that played). */
    Optional<FallbackTrackEntity> findFirstByPartyCodeAndPlaylistIdAndStatusOrderByPlayedAtDesc(
            String partyCode, String playlistId, FallbackTrackStatus status);

    /**
     * Takes a transaction-scoped PostgreSQL advisory lock: everything that re-orders or takes the tracks of one
     * party's fallback queue calls this first, so two such operations never run at the same time. Without it,
     * statements that update many rows at once (a re-order, a new round) could lock the same rows in different
     * orders and deadlock. The lock is released when the transaction ends.
     *
     * @param key identifies the party's queue, see {@code FallbackTrackCommandService#queueLockKey}
     * @return always 1 (the select only exists to run the locking function)
     */
    @Query(value = "SELECT 1 FROM (SELECT pg_advisory_xact_lock(:key)) l", nativeQuery = true)
    int lockQueue(@Param("key") long key);

    /**
     * Puts the tracks with this status into playlist order, continuing <em>after</em> {@code anchorPosition}: tracks
     * behind it come first, the ones before it follow (a rotation). {@code -1} means plain playlist order.
     * One conditional UPDATE — it never touches a track that changed status in the meantime. A fresh order means the
     * DJ's manual moves are gone, so the {@code manualMove} flags are cleared too.
     *
     * @return number of updated rows
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE FallbackTrackEntity t SET t.playOrder = CASE WHEN t.playlistPosition > :anchorPosition "
            + "THEN t.playlistPosition ELSE t.playlistPosition + :rotation END, t.manualMove = false "
            + "WHERE t.partyCode = :partyCode AND t.playlistId = :playlistId AND t.status = :status")
    int orderByPlaylistPosition(@Param("partyCode") String partyCode,
                                @Param("playlistId") String playlistId,
                                @Param("status") FallbackTrackStatus status,
                                @Param("anchorPosition") int anchorPosition,
                                @Param("rotation") int rotation);

    /**
     * Gives the tracks with this status a random order: every row gets a random key, so sorting by it is a uniform
     * shuffle (ties, about 1 in a billion, fall back to playlist position). One statement, done by the database.
     * Clears the {@code manualMove} flags — a new order replaces the DJ's manual moves.
     *
     * @param status the {@link FallbackTrackStatus} name — a native query cannot bind the enum itself
     * @return number of updated rows
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "UPDATE fallback_track SET play_order = CAST(floor(random() * 1000000000) AS integer), manual_move = false "
            + "WHERE party_code = :partyCode AND playlist_id = :playlistId AND status = :status", nativeQuery = true)
    int shuffle(@Param("partyCode") String partyCode,
                @Param("playlistId") String playlistId,
                @Param("status") String status);

    /**
     * Moves one queued track behind all the others of its playlist.
     *
     * @return 1 if the track was moved, 0 if it is no longer queued
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE FallbackTrackEntity t SET t.playOrder = (SELECT MAX(x.playOrder) FROM FallbackTrackEntity x "
            + "WHERE x.partyCode = t.partyCode AND x.playlistId = t.playlistId AND x.status = :status) + 1 "
            + "WHERE t.id = :id AND t.status = :status")
    int moveToEnd(@Param("id") Long id, @Param("status") FallbackTrackStatus status);

    /**
     * The DJ's "play next": moves one queued track in front of all the others of its playlist and flags it as moved
     * by hand.
     *
     * @return 1 if the track was moved, 0 if it is no longer queued
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE FallbackTrackEntity t SET t.playOrder = (SELECT MIN(x.playOrder) FROM FallbackTrackEntity x "
            + "WHERE x.partyCode = t.partyCode AND x.playlistId = t.playlistId AND x.status = :status) - 1, "
            + "t.manualMove = true WHERE t.id = :id AND t.status = :status")
    int moveToFront(@Param("id") Long id, @Param("status") FallbackTrackStatus status);

    /**
     * Gives the queued tracks of a playlist the play orders 0, 1, 2, … in their current order, so that no two of them
     * share an order and two neighbours can be swapped by exchanging their values. Does not change the sequence and
     * leaves the {@code manualMove} flags alone.
     *
     * @param status the {@link FallbackTrackStatus} name — a native query cannot bind the enum itself
     * @return number of updated rows
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "UPDATE fallback_track t SET play_order = r.rn FROM ("
            + "SELECT id, CAST(row_number() OVER (ORDER BY play_order, playlist_position) - 1 AS integer) AS rn "
            + "FROM fallback_track WHERE party_code = :partyCode AND playlist_id = :playlistId AND status = :status) r "
            + "WHERE t.id = r.id", nativeQuery = true)
    int renumberQueued(@Param("partyCode") String partyCode,
                       @Param("playlistId") String playlistId,
                       @Param("status") String status);

    /**
     * Sets the play order of one queued track (a step of moving it up or down) and flags it as moved by hand.
     *
     * @return 1 if the order was set, 0 if the track is no longer queued
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE FallbackTrackEntity t SET t.playOrder = :playOrder, t.manualMove = true "
            + "WHERE t.id = :id AND t.status = :status")
    int setPlayOrderByHand(@Param("id") Long id,
                           @Param("playOrder") int playOrder,
                           @Param("status") FallbackTrackStatus status);

    /**
     * Adds {@code delta} to the play order of the queued tracks whose order lies between {@code from} and {@code to}
     * (both included) — makes room for a track dragged to a new place, or closes the gap it left. Only meaningful
     * right after {@link #renumberQueued}, when the orders are exactly 0, 1, 2, ...; does not flag anything.
     *
     * @return number of shifted rows
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE FallbackTrackEntity t SET t.playOrder = t.playOrder + :delta "
            + "WHERE t.partyCode = :partyCode AND t.playlistId = :playlistId AND t.status = :status "
            + "AND t.playOrder >= :from AND t.playOrder <= :to")
    int shiftPlayOrder(@Param("partyCode") String partyCode,
                       @Param("playlistId") String playlistId,
                       @Param("status") FallbackTrackStatus status,
                       @Param("from") int from,
                       @Param("to") int to,
                       @Param("delta") int delta);

    /** Whether the DJ has moved any of the queued tracks of this playlist by hand. */
    boolean existsByPartyCodeAndPlaylistIdAndStatusAndManualMoveTrue(String partyCode, String playlistId,
                                                                     FallbackTrackStatus status);

    /** A track by ID, but only if it belongs to the party (a DJ must never reach another party's tracks). */
    Optional<FallbackTrackEntity> findByIdAndPartyCode(Long id, String partyCode);

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
