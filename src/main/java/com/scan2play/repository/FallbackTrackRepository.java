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

import java.time.Instant;
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

    /**
     * Inserts an imported playlist in one statement: the i-th video gets playlist position and play order i (playlist order),
     * all with the same status and fetch time. One round trip for up to 500 tracks, under the queue's lock — saving the
     * entities was one INSERT per track, as identity ids cannot be batched.
     *
     * @param videoIds the videos in playlist order
     * @param titles   their titles, same length (an element may be null)
     * @param status   the {@link FallbackTrackStatus} name — a native query cannot bind the enum itself
     * @return number of inserted rows
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "INSERT INTO fallback_track (party_code, playlist_id, video_id, title, playlist_position, play_order, status, "
            + "fetched_at, manual_move) "
            + "SELECT :partyCode, :playlistId, t.video_id, t.title, CAST(t.n - 1 AS integer), CAST(t.n - 1 AS integer), :status, "
            + ":fetchedAt, false "
            + "FROM unnest(CAST(:videoIds AS varchar[]), CAST(:titles AS varchar[])) WITH ORDINALITY AS t(video_id, title, n)",
            nativeQuery = true)
    int insertTracks(@Param("partyCode") String partyCode,
                     @Param("playlistId") String playlistId,
                     @Param("videoIds") String[] videoIds,
                     @Param("titles") String[] titles,
                     @Param("status") String status,
                     @Param("fetchedAt") Instant fetchedAt);

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
     * The DJ skips a queued track for this round: QUEUED → SKIPPED in a single conditional UPDATE (it never touches a track
     * the player took in the meantime). The track comes back when the playlist starts its next round
     * ({@link #requeuePlayedTracks}).
     *
     * @return 1 if the track was skipped, 0 if it is no longer queued
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE FallbackTrackEntity t SET t.status = :skipped WHERE t.id = :id AND t.status = :queued")
    int markSkipped(@Param("id") Long id,
                    @Param("queued") FallbackTrackStatus queued,
                    @Param("skipped") FallbackTrackStatus skipped);

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

    /**
     * A fingerprint of what the "up next" list of this playlist shows: the queued tracks in play order with their "moved by
     * hand" flags, and how many are skipped — an md5 computed by the database, so no row leaves it. It changes with every
     * hand-out, move, drag, skip, new order and import (new ids), and only then. Read on every lease report (every 3 s from
     * every dashboard window), over {@code idx_fallback_track_queue}.
     *
     * @param queued  the {@link FallbackTrackStatus#QUEUED} name — a native query cannot bind the enum itself
     * @param skipped the {@link FallbackTrackStatus#SKIPPED} name
     */
    @Query(value = "SELECT md5(COALESCE(string_agg(CASE WHEN status = :queued THEN id || CASE WHEN manual_move THEN 'm' ELSE '' END END, "
            + "',' ORDER BY play_order, playlist_position), '') || '|' || count(*) FILTER (WHERE status = :skipped)) "
            + "FROM fallback_track WHERE party_code = :partyCode AND playlist_id = :playlistId AND status IN (:queued, :skipped)",
            nativeQuery = true)
    String queueFingerprint(@Param("partyCode") String partyCode,
                            @Param("playlistId") String playlistId,
                            @Param("queued") String queued,
                            @Param("skipped") String skipped);

    /** Whether the DJ has moved any of the queued tracks of this playlist by hand. */
    boolean existsByPartyCodeAndPlaylistIdAndStatusAndManualMoveTrue(String partyCode, String playlistId,
                                                                     FallbackTrackStatus status);

    /** A track by ID, but only if it belongs to the party (a DJ must never reach another party's tracks). */
    Optional<FallbackTrackEntity> findByIdAndPartyCode(Long id, String partyCode);

    /** When the newest import of this playlist for the party was fetched (empty if it was never imported). */
    @Query("SELECT MAX(t.fetchedAt) FROM FallbackTrackEntity t WHERE t.partyCode = :partyCode AND t.playlistId = :playlistId")
    Optional<Instant> findLatestFetchedAt(@Param("partyCode") String partyCode,
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
                         @Param("now") Instant now);

    /**
     * Puts the already-played tracks — and the ones the DJ skipped in this round — of the party's <em>newest</em> import
     * back in the queue (the playlist loops). "Newest" is party-wide, not per playlist: a batch that a later import — of
     * the same or of another playlist — has superseded is history and is never revived. If the newest import belongs to a
     * different playlist than {@code playlistId}, nothing matches and 0 is returned.
     * <p>
     * This clears {@code playedAt}, so a track's own row says only what happened in the <em>current</em> round; what has
     * played over the whole party is in the play log ({@code fallback_play}, {@link FallbackPlayRepository}).
     *
     * @return number of tracks re-queued
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE FallbackTrackEntity t SET t.status = :queued, t.playedAt = null "
            + "WHERE t.partyCode = :partyCode AND t.playlistId = :playlistId "
            + "AND (t.status = :played OR t.status = com.scan2play.model.FallbackTrackStatus.SKIPPED) "
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
    int deleteFetchedBefore(@Param("cutoff") Instant cutoff);
}
