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
     * Finds the 300 oldest song requests for the DJ dashboard queue (FIFO order) — as many as a party may send in a day
     * ({@code guest.limit.per-party-daily}), so every waiting request is in it: the DJ's queue, the guests' list and their 👍, and
     * the match of a request for a song that waits already (a vote, not a second row). It was 100 until 2026-10-08: a song asked
     * for again while 100 older ones waited became a second row. Bounded; {@code idx_party_decision_time} finds the rows.
     *
     * @param partyCode The unique code of the party.
     * @param decisions The list of statuses to include (e.g., ["accepted"]).
     * @return A list of the 300 oldest matching song requests, ordered by oldest first.
     */
    List<SongRequestEntity> findTop300ByPartyCodeAndDecisionInOrderByRequestedAtAsc(String partyCode, Collection<String> decisions);

    /**
     * The party's requests the DJ skipped ("Pomiń", {@code skipped_at}, V20) after {@code since}, the latest skip first — a request
     * for one of them again does not come back to the DJ's queue ({@code SongRequestCommandService}). Bounded by the pageable;
     * {@code idx_party_decision_time} narrows it to the party's rejected requests.
     */
    @Query("SELECT s FROM SongRequestEntity s WHERE s.partyCode = :partyCode AND s.decision = 'rejected' AND s.skippedAt > :since "
            + "ORDER BY s.skippedAt DESC")
    List<SongRequestEntity> findSkippedByTheDj(@Param("partyCode") String partyCode, @Param("since") Instant since,
                                               Pageable pageable);

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
     * The party's evenings, the latest first: each day ({@code "2026-10-03"}) a request was made on, with how many — the day in
     * Polish time, moved back by {@code EveningSummaryService.EVENING_STARTS} (6 hours), so a request at 01:30 belongs to the evening
     * before. A row is {@code [day as text, count]}. Bounded by {@code limit}; the retention keeps 30 days of one party's rows
     * (≤ 300 a day), {@code idx_party_decision_time} finds them by its first column.
     */
    @Query(value = "SELECT to_char(CAST((requested_at AT TIME ZONE 'Europe/Warsaw') - INTERVAL '6 hours' AS date), 'YYYY-MM-DD'),"
            + " COUNT(*) FROM song_requests WHERE party_code = :partyCode GROUP BY 1 ORDER BY 1 DESC LIMIT :limit",
            nativeQuery = true)
    List<Object[]> findEvenings(@Param("partyCode") String partyCode, @Param("limit") int limit);

    /**
     * The party's requests made in {@code [from, to)}, every decision, the first asked first — one evening of the summary
     * ({@code EveningSummaryService}). Bounded by the pageable.
     */
    @Query("SELECT s FROM SongRequestEntity s WHERE s.partyCode = :partyCode AND s.requestedAt >= :from AND s.requestedAt < :to "
            + "ORDER BY s.requestedAt, s.id")
    List<SongRequestEntity> findRequestedBetween(@Param("partyCode") String partyCode, @Param("from") Instant from,
                                                 @Param("to") Instant to, Pageable pageable);

    /**
     * Computes a lightweight fingerprint of the queue state (count + maxId + the votes and the DJ's tips, which change no row
     * count). Used for ETag-based 304 Not Modified responses during AJAX polling.
     *
     * @param partyCode The unique code of the party.
     * @param decisions The list of statuses to include.
     * @return A string like "12-487-15-2" (count-maxId-votes-tips), or "0-0-0-0" if empty.
     */
    @Query("SELECT CONCAT(COUNT(s), '-', COALESCE(MAX(s.id), 0), '-', COALESCE(SUM(s.votes), 0), '-', COALESCE(SUM(s.tips), 0)) " +
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
     * A guest's 👍 on a song in the list of the party page ({@code GuestVoteService}): one vote more, only for a song of this party
     * that still waits — the id comes from the guest, so the party is part of the condition. Atomic, as {@link #addVote}: no lock
     * needed, a vote that meets the DJ's "played" either counts or finds the song gone.
     *
     * @return 1 when the vote was added, 0 when the song no longer waits (or is not this party's)
     */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE SongRequestEntity s SET s.votes = s.votes + 1 "
            + "WHERE s.id = :id AND s.partyCode = :partyCode AND s.decision = 'accepted'")
    int addGuestVote(@Param("id") Long id, @Param("partyCode") String partyCode);

    /**
     * A guest takes their 👍 back: one vote less, never below 1 — the guest who asked for the song first is not a 👍 to take back.
     *
     * @return 1 when a vote was taken back, 0 when the song no longer waits (or has one vote, or is not this party's)
     */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE SongRequestEntity s SET s.votes = s.votes - 1 "
            + "WHERE s.id = :id AND s.partyCode = :partyCode AND s.decision = 'accepted' AND s.votes > 1")
    int removeGuestVote(@Param("id") Long id, @Param("partyCode") String partyCode);

    /**
     * The next number of a song at the party (V28): the party's count one up, in the same statement — a song that reaches the queue
     * gets it ({@code SongRequestCommandService}, under the party's advisory lock). The count is never lowered, so a number is never
     * given twice, also after "Wyczyść historię" deleted the songs that had the last ones.
     *
     * @return the number, from 1; null when the party has no settings row (a request is never saved for one — only a test does)
     */
    @Query(value = "UPDATE party_settings SET request_counter = request_counter + 1 WHERE party_code = :partyCode "
            + "RETURNING request_counter", nativeQuery = true)
    Integer nextRequestNumber(@Param("partyCode") String partyCode);

    /** The party's highest number so far — the next one without a settings row ({@link #nextRequestNumber}). */
    @Query(value = "SELECT COALESCE(MAX(request_number), 0) FROM song_requests WHERE party_code = :partyCode", nativeQuery = true)
    int maxRequestNumber(@Param("partyCode") String partyCode);

    /**
     * The DJ counts a tip for a song of their party (V28: a payment titled "#27" came in): one more, on a song with a number —
     * whatever its decision now (the DJ taps "💸" in the queue, the song may have played meanwhile in another window). One atomic
     * {@code UPDATE}; the party is part of the condition (the id comes from the DJ's form).
     *
     * @return 1 when it was counted, 0 when the song is not this party's or has no number
     */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE SongRequestEntity s SET s.tips = s.tips + 1 "
            + "WHERE s.id = :id AND s.partyCode = :partyCode AND s.requestNumber IS NOT NULL")
    int addTip(@Param("id") Long id, @Param("partyCode") String partyCode);

    /**
     * The DJ takes back a tip counted by mistake: one less, never below 0.
     *
     * @return 1 when one was taken back, 0 when there was none (or the song is not this party's)
     */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE SongRequestEntity s SET s.tips = s.tips - 1 WHERE s.id = :id AND s.partyCode = :partyCode AND s.tips > 0")
    int removeTip(@Param("id") Long id, @Param("partyCode") String partyCode);

    /**
     * The DJ clears the queue: every waiting (accepted) request of the party leaves it as rejected, with {@code clearedAt} set and
     * the AI's comment kept — it stays in the history's rejected requests, marked as cleared. One statement; a song that played
     * stays played.
     *
     * @return number of requests taken out of the queue
     */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE SongRequestEntity s SET s.decision = 'rejected', s.clearedAt = :clearedAt "
            + "WHERE s.partyCode = :partyCode AND s.decision = 'accepted'")
    int rejectWaiting(@Param("partyCode") String partyCode, @Param("clearedAt") Instant clearedAt);

    /**
     * The DJ clears the history: the party's requests that played or were rejected are deleted, guests' words with them. Never a
     * waiting one, and never one the DJ skipped after {@code skippedAfter}: it keeps its song out of the queue
     * ({@link #findSkippedByTheDj}) and stays in the history until that ends. One statement over one party's rows — at most what
     * the retention keeps of it (30 days, ≤ 300 requests a day), found by {@code idx_party_decision_time}.
     *
     * @return number of deleted requests
     */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM SongRequestEntity s WHERE s.partyCode = :partyCode AND s.decision IN ('played', 'rejected') "
            + "AND (s.skippedAt IS NULL OR s.skippedAt <= :skippedAfter)")
    int deleteHistory(@Param("partyCode") String partyCode, @Param("skippedAfter") Instant skippedAfter);

    /**
     * After "Wyczyść historię" (V28): the party's count of numbers goes back to the highest number still in use — 0 when no
     * numbered song is left (the queue empty), so the next song is #1 again; never a number a song still has. Run under the
     * party's advisory lock ({@code DjService.clearHistory}), as the numbers are given.
     */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "UPDATE party_settings SET request_counter = (SELECT COALESCE(MAX(request_number), 0) FROM song_requests "
            + "WHERE party_code = :partyCode) WHERE party_code = :partyCode", nativeQuery = true)
    int resetRequestCounter(@Param("partyCode") String partyCode);

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
