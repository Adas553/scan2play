package com.scan2play.repository;

import com.scan2play.entity.SongRequestEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

@Repository
public interface SongRequestRepository extends JpaRepository<SongRequestEntity, Long> {

    /**
     * Finds the top 100 oldest song requests for the DJ dashboard queue (FIFO order).
     * Oldest request is first — DJ sees what will play next at the top of the list.
     *
     * @param partyCode The unique code of the party.
     * @param decisions The list of statuses to include (e.g., ["accepted"]).
     * @return A list of the top 100 matching song requests, ordered by oldest first.
     */
    List<SongRequestEntity> findTop100ByPartyCodeAndDecisionInOrderByRequestedAtAsc(String partyCode, Collection<String> decisions);

    /**
     * The party's requests the DJ skipped ("Pomiń": rejected, with the DJ's note {@code comment}) that were asked for after
     * {@code since}, the latest first — a request for one of them again does not come back to the DJ's queue
     * ({@code SongRequestCommandService}). Bounded by the pageable; {@code idx_party_decision_time} covers the filter.
     */
    @Query("SELECT s FROM SongRequestEntity s WHERE s.partyCode = :partyCode AND s.decision = 'rejected' AND s.djComment = :comment "
            + "AND s.requestedAt > :since ORDER BY s.requestedAt DESC")
    List<SongRequestEntity> findSkippedByTheDj(@Param("partyCode") String partyCode, @Param("comment") String comment,
                                               @Param("since") Instant since, Pageable pageable);

    /**
     * The party's songs that played most recently — the ones the AI must not accept again (its duplicate rule). By the moment they
     * played, not when they were asked for: a song asked for early and played just now is one of the last. A song played before
     * V6 (no {@code played_at}) counts by its request time, as in the history.
     *
     * @param partyCode The unique code of the party.
     * @param pageable  How many to read.
     * @return The songs that played, the latest first.
     */
    @Query("SELECT s FROM SongRequestEntity s WHERE s.partyCode = :partyCode AND s.decision = 'played' "
            + "ORDER BY COALESCE(s.playedAt, s.requestedAt) DESC, s.id DESC")
    List<SongRequestEntity> findRecentlyPlayed(@Param("partyCode") String partyCode, Pageable pageable);

    /**
     * The party's played and/or rejected requests, the most recent event first — for the DJ history
     * ({@code PlayHistoryService}). The moment of an event is when the request was played, or, for a rejected one or one
     * that was played before V6 (no {@code played_at}), when it was requested. The read is bounded by the pageable and
     * limited to one party and the given decisions; sorting the party's rows on the expression is cheap next to that.
     *
     * @param partyCode The unique code of the party.
     * @param decisions The decisions to include (played, rejected).
     * @param pageable  How many to read.
     * @return The most recent requests, newest event first.
     */
    @Query("SELECT s FROM SongRequestEntity s WHERE s.partyCode = :partyCode AND s.decision IN :decisions "
            + "ORDER BY COALESCE(s.playedAt, s.requestedAt) DESC, s.id DESC")
    List<SongRequestEntity> findHistory(@Param("partyCode") String partyCode,
                                        @Param("decisions") Collection<String> decisions,
                                        Pageable pageable);

    /**
     * Computes a lightweight fingerprint of the queue state (count + maxId + the votes, which change no row count).
     * Used for ETag-based 304 Not Modified responses during AJAX polling.
     *
     * @param partyCode The unique code of the party.
     * @param decisions The list of statuses to include.
     * @return A string like "12-487-15" (count-maxId-votes), or "0-0-0" if empty.
     */
    @Query("SELECT CONCAT(COUNT(s), '-', COALESCE(MAX(s.id), 0), '-', COALESCE(SUM(s.votes), 0)) " +
           "FROM SongRequestEntity s WHERE s.partyCode = :partyCode AND s.decision IN :decisions")
    String computeFingerprint(@Param("partyCode") String partyCode,
                              @Param("decisions") Collection<String> decisions);

    /**
     * Takes a transaction-scoped PostgreSQL advisory lock on the party's guest requests: saving a request and adding a vote to a
     * waiting one take turns, so two guests who ask for the same song at the same moment make one row with two votes, not two
     * rows. Released when the transaction ends.
     *
     * @param key identifies the party's requests, see {@code SongRequestCommandService#lockKey}
     * @return always 1 (the select only exists to run the locking function)
     */
    @Query(value = "SELECT 1 FROM (SELECT pg_advisory_xact_lock(:key)) l", nativeQuery = true)
    int lockRequests(@Param("key") long key);

    /**
     * One more guest asked for this song — only while it still waits ({@code accepted}): a song that has just been played or
     * skipped is not counted.
     *
     * Clears the persistence context afterwards: the caller's copy of the row is then detached, so changing its count in memory
     * never writes the whole row back (it could put back a decision the DJ has just changed).
     *
     * @return 1 when the vote was added, 0 when the song no longer waits
     */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE SongRequestEntity s SET s.votes = s.votes + 1 WHERE s.id = :id AND s.decision = 'accepted'")
    int addVote(@Param("id") Long id);

    /**
     * The DJ clears the queue: every waiting (accepted) request of the party leaves it as rejected, with the DJ's note in place of
     * the AI's comment — it stays in the history's rejected requests. One statement; a song that played stays played.
     *
     * @return number of requests taken out of the queue
     */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE SongRequestEntity s SET s.decision = 'rejected', s.djComment = :comment "
            + "WHERE s.partyCode = :partyCode AND s.decision = 'accepted'")
    int rejectWaiting(@Param("partyCode") String partyCode, @Param("comment") String comment);

    /**
     * Deletes all song requests associated with a specific party.
     * Used during account deletion to comply with GDPR / Google API data deletion requirements.
     *
     * @param partyCode The unique code of the party.
     */
    void deleteByPartyCode(String partyCode);

    /**
     * Deletes at most {@code batchSize} song requests that were requested before {@code cutoff} — the retention purge
     * ({@code SongRequestRetentionService}); any decision, played or not. A request without a {@code requested_at} has no age
     * and is left alone. Bounded on purpose: the caller repeats it until a batch comes back short, so that a first run over
     * a long backlog is many short transactions (short locks) and not one huge DELETE. Each call is its own transaction.
     *
     * @return number of deleted rows (less than {@code batchSize} when nothing older is left)
     */
    @Transactional
    @Modifying
    @Query(value = "DELETE FROM song_requests WHERE id IN "
            + "(SELECT id FROM song_requests WHERE requested_at < :cutoff LIMIT :batchSize)", nativeQuery = true)
    int deleteRequestedBefore(@Param("cutoff") Instant cutoff, @Param("batchSize") int batchSize);
}
